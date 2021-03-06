//    Copyright 2014, 2015 Bremeld Corp SA (Montevideo, Uruguay)
//    https://www.linkedin.com/company/bremeld
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.

package org.bremeld.solr.undertow

import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.predicate.Predicates
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.GracefulShutdownHandler
import io.undertow.server.handlers.RequestLimit
import io.undertow.server.handlers.accesslog.AccessLogHandler
import io.undertow.server.handlers.accesslog.AccessLogReceiver
import io.undertow.server.handlers.resource.FileResourceManager
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.DefaultServletConfig
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.MimeMapping
import io.undertow.util.Headers
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import javax.servlet.DispatcherType
import javax.servlet.Filter
import javax.servlet.Servlet
import kotlin.properties.Delegates

public data class ServerStartupStatus(val started: Boolean, val errorMessage: String)

public class Server(cfgLoader: ServerConfigLoader) {
    val log = LoggerFactory.getLogger("SolrServer")
    val cfg by Delegates.lazy { ServerConfig(log, cfgLoader) }
    var server: Undertow by Delegates.notNull()

    public fun run(): ServerStartupStatus {
        log.warn("Solr + Undertow = small server, happy days, fast, and maybe other cool things.")
        try {
            log.warn("Starting SolrServer")
            if (!cfg.validate()) {
                return ServerStartupStatus(false, "Configuration is not valid, terminating server")
            }
            cfg.print()

            val solrWarDeployment = deployWarFileToCache(cfg.solrWarFile)
            if (!solrWarDeployment.successfulDeploy) {
                return ServerStartupStatus(false, "WAR file failed to deploy, terminating server")
            }

            val ioThreads = (if (cfg.httpIoThreads == 0) Runtime.getRuntime().availableProcessors() else cfg.httpIoThreads).minimum(2)
            val workerThreads = (if (cfg.httpWorkerThreads == 0) Runtime.getRuntime().availableProcessors() * 8 else cfg.httpWorkerThreads).minimum(8)

            val (servletDeploymentMgr, servletHandler) = buildSolrServletHandler(solrWarDeployment)

            val loggedHandler = AccessLogHandler(servletHandler, object : AccessLogReceiver {
                override fun logMessage(message: String?) {
                    cfg.accessLogger.info(message)
                }
            }, cfg.accessLogFormat, javaClass<Server>().getClassLoader())

            val gracefulShutdownWrapperHandler = Handlers.gracefulShutdown(loggedHandler)

            // ensure we attempt to close things nicely on termination
            val gracefulShutdownHook = Thread() {
                if (servletDeploymentMgr.getState() == DeploymentManager.State.STARTED) {
                    log.warn("Shutdown Hook triggered:  Undeploying servlets...")
                    val wasGraceful = shutdownNicely(servletDeploymentMgr, gracefulShutdownWrapperHandler)
                    if (wasGraceful) {
                        log.warn("Undeploy complete and graceful.")
                    } else {
                        log.error("Undeploy complete, but shutdown was not graceful.")
                    }
                } else {
                    log.warn("Shutdown Hook triggered: servlets already undeployed.")
                }
            }
            Runtime.getRuntime().addShutdownHook(gracefulShutdownHook)

            val shutdownRequestHandler = ShutdownRequestHandler(servletDeploymentMgr, gracefulShutdownWrapperHandler)

            log.info("Building Undertow server [port:${cfg.httpClusterPort},host:${cfg.httpHost},ioThreads:${ioThreads},workerThreads:${workerThreads}]")
            server = Undertow.builder()
                    .addHttpListener(cfg.httpClusterPort, cfg.httpHost)
                    .addHttpListener(cfg.shutdownConfig.httpPort, cfg.shutdownConfig.httpHost, shutdownRequestHandler)
                    .setDirectBuffers(true)
                    .setHandler(gracefulShutdownWrapperHandler)
                    .setIoThreads(ioThreads)
                    .setWorkerThreads(workerThreads)
                    .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, cfg.accessLogEnableRequestTiming)
                    .build()

            log.warn("Starting Undertow HTTP server...")
            server.start()
            val listeningUrl = "http://${cfg.httpHost}:${cfg.httpClusterPort}${cfg.solrContextPath}"
            log.warn("Undertow HTTP Server started, listening on ${listeningUrl}")

            val shutdownUrl = "http://${cfg.shutdownConfig.httpHost}:${cfg.shutdownConfig.httpPort}?password=*****"
            log.warn("Shutdown port configured as ${shutdownUrl} with graceful delay of ${cfg.shutdownConfig.gracefulDelayString}")
            if (cfg.shutdownConfig.password.isNullOrBlank()) {
                log.warn("Shutdown password is not configured, therefore shutdown requests via HTTP will fail.")
            }

            log.info("Loading one Solr admin page to confirm not in a wait state...")
            // trigger a Solr servlet in case Solr is waiting to start still
            // TODO: change this to a HTTP request with status check (200), terminate server on 500?  (allow for long timeout, we could be waiting on node joins and startup delays)
            URL("${listeningUrl}/#/").readBytes()
            log.warn("!!!! SERVER READY:  Listening, and ready at ${listeningUrl} !!!!")

            return ServerStartupStatus(true, "OK")
        } catch (ex: Throwable) {
            log.error("Server unhandled exception during startup '${ex.getMessage() ?: ex.javaClass.getName()}'", ex)
            return ServerStartupStatus(false, "Server unhandled exception during startup '${ex.getMessage()}'")
        }
    }

    private data class DeployedWarInfo(val successfulDeploy: Boolean, val cacheDir: Path, val htmlDir: Path, val libDir: Path, val classLoader: ClassLoader)
    private data class ServletDeploymentAndHandler(val deploymentManager: DeploymentManager, val pathHandler: HttpHandler)

    private inner class ShutdownRequestHandler(val servletDeploymentMgr: DeploymentManager, val gracefulShutdownWrapperHandler: GracefulShutdownHandler) : HttpHandler {
        override fun handleRequest(exchange: HttpServerExchange) {
            if (cfg.shutdownConfig.password.isNullOrBlank()) {
                exchange.setResponseCode(403).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                exchange.getResponseSender().send("forbidden")
                log.error("Forbidden attempt to talk to shutdown port")
            } else if (exchange.getQueryParameters().get("password")?.firstOrNull() != cfg.shutdownConfig.password) {
                exchange.setResponseCode(401).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                exchange.getResponseSender().send("unauthorized")
                log.error("Unauthorized attempt to talk to shutdown port")
            } else {
                if (exchange.isInIoThread()) {
                    exchange.dispatch(this);
                    return;
                }

                log.warn("Shutdown requested via shutdown port...")
                val wasGraceful = shutdownNicely(servletDeploymentMgr, gracefulShutdownWrapperHandler)
                if (wasGraceful) {
                    try {
                        exchange.setResponseCode(200).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                        exchange.getResponseSender().send("OK")
                    } finally {
                        server.stop()
                        log.warn("Undeploy complete and graceful.")
                    }
                } else {
                    try {
                        exchange.setResponseCode(500).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                        exchange.getResponseSender().send("ERROR")
                    } finally {
                        server.stop()
                        log.error("Undeploy complete, but shutdown was not graceful.")
                        System.exit(1)
                    }
                }

            }
        }
    }

    private fun shutdownNicely(servletDeploymentMgr: DeploymentManager, gracefulShutdownWrapperHandler: GracefulShutdownHandler): Boolean {
        // block traffic first
        gracefulShutdownWrapperHandler.shutdown()
        // wait for a moment for things to finish that are not blocked
        val wasGraceful = gracefulShutdownWrapperHandler.awaitShutdown(cfg.shutdownConfig.gracefulDelay)
        // undeploy and hope everyone closes down nicely
        servletDeploymentMgr.stop()
        servletDeploymentMgr.undeploy()
        return wasGraceful
    }

    private fun deployWarFileToCache(solrWar: Path): DeployedWarInfo {
        log.warn("Extracting WAR file: ${solrWar}")

        val tempDirThisSolr = cfg.tempDir.resolve(cfg.solrVersion)
        val tempDirHtml = tempDirThisSolr.resolve("html-root")
        val tempDirJars = tempDirThisSolr.resolve("lib")

        tempDirThisSolr.deleteRecursive()
        Files.createDirectories(tempDirThisSolr)
        Files.createDirectories(tempDirHtml)
        Files.createDirectories(tempDirJars)

        val FAILED_DEPLOYMENT = DeployedWarInfo(false, tempDirThisSolr, tempDirHtml, tempDirJars, ClassLoader.getSystemClassLoader())

        val warPathForUri = solrWar.normalize().toAbsolutePath().toString().replace(File.separatorChar, '/').replace('\\', '/')
        val warUri1 = URI("jar:file", warPathForUri.mustStartWith('/'), null)

        val warJarFs = try {
            log.warn("  ${warUri1}")
            FileSystems.newFileSystem(warUri1, mapOf("create" to "false"))
        } catch (ex: Throwable) {
            log.error("The WAR file ${solrWar} cannot be opened as a Zip file, due to '${ex.getMessage() ?: ex.javaClass.getName()}'", ex)
            return FAILED_DEPLOYMENT
        }
        val warLibPath = warJarFs.getPath("/WEB-INF/lib/")
        val warRootPath = warJarFs.getPath("/")

        if (warLibPath.notExists() || !Files.isDirectory(warLibPath)) {
            log.error("The WAR file ${solrWar} does not contain WEB-INF/lib/ directory for the classpath jars")
            return FAILED_DEPLOYMENT
        }

        val jarFiles = ArrayList<URL>()
        Files.newDirectoryStream(warLibPath, "*.jar").forEach { zippedJar ->
            val jarDestination = tempDirJars.resolve(zippedJar.getFileName().toString())
            Files.copy(zippedJar, jarDestination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            jarFiles.add(jarDestination.toUri().toURL())
        }

        if (cfg.hasLibExtDir()) {
            Files.newDirectoryStream(cfg.libExtDir, "*.jar").forEach { extJar ->
                jarFiles.add(extJar.toUri().toURL())
            }
        }

        var warCopyFailed = false

        Files.walkFileTree(warRootPath, object : FileVisitor<Path?> {
            override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes): FileVisitResult {
                // create dir in target
                val relativeSourceDir = warRootPath.relativize(dir!!).toString()
                if (relativeSourceDir.isEmpty()) {
                    return FileVisitResult.CONTINUE
                } else if (relativeSourceDir == "WEB-INF/" || relativeSourceDir == "META-INF/") {
                    return FileVisitResult.SKIP_SUBTREE
                } else {
                    val destination = tempDirHtml.resolve(relativeSourceDir)
                    try {
                        Files.copy(dir, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    } catch (ex: FileAlreadyExistsException) {
                        // that's ok
                    }
                    return FileVisitResult.CONTINUE
                }
            }

            override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                // fix modification time of dir
                if (exc == null) {
                    val destination = tempDirHtml.resolve(warRootPath.relativize(dir!!).toString())
                    val lastModTime = Files.getLastModifiedTime(dir)
                    Files.setLastModifiedTime(destination, lastModTime)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path?, attrs: BasicFileAttributes): FileVisitResult {
                val destination = tempDirHtml.resolve(warRootPath.relativize(file!!).toString())
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path?, ex: IOException?): FileVisitResult {
                log.error("Unable to copy from WAR to temp directory, file ${file?.toAbsolutePath().toString()}, due to '${ex?.getMessage() ?: ex?.javaClass?.getName() ?: "unknown"}'", ex)
                warCopyFailed = true
                return FileVisitResult.TERMINATE
            }
        })

        if (warCopyFailed) {
            log.error("The WAR file ${solrWar} could not be copied to temp directory")
            return FAILED_DEPLOYMENT
        }

        return DeployedWarInfo(true, tempDirThisSolr, tempDirHtml, tempDirJars, URLClassLoader(jarFiles.toTypedArray(), ClassLoader.getSystemClassLoader()))
    }

    private fun buildSolrServletHandler(solrWarDeployment: DeployedWarInfo): ServletDeploymentAndHandler {
        // load all by name so we have no direct dependency on Solr
        val solrDispatchFilterClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.SolrDispatchFilter").asSubclass(javaClass<Filter>())
        val solrZookeeprServletClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.ZookeeperInfoServlet").asSubclass(javaClass<Servlet>())
        val solrAdminUiServletClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.LoadAdminUiServlet").asSubclass(javaClass<Servlet>())
        val solrRestApiServletClass = solrWarDeployment.classLoader.loadClass("org.restlet.ext.servlet.ServerServlet").asSubclass(javaClass<Servlet>())
        val solrRestApiClass = try {
            solrWarDeployment.classLoader.loadClass("org.apache.solr.rest.SolrSchemaRestApi")
        } catch (ex: ClassNotFoundException) {
            solrWarDeployment.classLoader.loadClass("org.apache.solr.rest.SolrRestApi")
        }
        val solrRestConfigApiClass: Class<*>? = try {
            solrWarDeployment.classLoader.loadClass("org.apache.solr.rest.SolrConfigRestApi")
        } catch (ex: ClassNotFoundException) {
            null
        }


        // mimic solr web.xml file, minus old deprecated redirects from old UI...
        val deployment = Servlets.deployment()
                .setClassLoader(solrWarDeployment.classLoader)
                .setContextPath(cfg.solrContextPath)
                .setDefaultServletConfig(SolrDefaultServletConfig())
                .setDefaultEncoding("UTF-8")
                .setDeploymentName("solr.war")
                .setEagerFilterInit(true)
                .addFilters(
                        Servlets.filter("SolrRequestFilter", solrDispatchFilterClass)
                                .addInitParam("path-prefix", null) // do we need to set thisif context path is more than one level deep?
                )
                .addFilterUrlMapping("SolrRequestFilter", "/*", DispatcherType.REQUEST)
                .addServlets(
                        Servlets.servlet("Zookeeper", solrZookeeprServletClass)
                                .addMapping("/zookeeper"),
                        Servlets.servlet("LoadAdminUI", solrAdminUiServletClass)
                                .addMapping("/admin.html"),
                        Servlets.servlet("SolrRestApi", solrRestApiServletClass)
                                .addInitParam("org.restlet.application", solrRestApiClass.getName())
                                .addMapping("/schema/*")
                )
                .addMimeMapping(MimeMapping(".xsl", "application/xslt+xml"))
                .addWelcomePage("admin.html")
                .setResourceManager(FileResourceManager(solrWarDeployment.htmlDir.toFile(), 1024))

        if (solrRestConfigApiClass != null) {
            deployment.addServlet(Servlets.servlet("SolrConfigRestApi", solrRestApiServletClass)
                    .addInitParam("org.restlet.application", solrRestConfigApiClass.getName())
                    .addMapping("/config/*"))
        }

        log.warn("Initializing Solr, deploying the Servlet Container...")
        val deploymentManager = Servlets.defaultContainer().addDeployment(deployment)
        deploymentManager.deploy()
        val deploymentHandler = deploymentManager.start()
        log.warn("Solr Servlet Container deployed.")

        // create nested request handlers that route to rate limiting handlers put suffix on inside,
        // and exact matches on outside so they are checked first.

        var wrappedHandlers = deploymentHandler
        cfg.requestLimiters.values() map { RequestLimitHelper(it) } forEach { rl ->
            wrappedHandlers = rl.nestHandlerInsideRateLimitingBySuffixHandler(wrappedHandlers, deploymentHandler)
        }
        cfg.requestLimiters.values() map { RequestLimitHelper(it) } forEach { rl ->
            wrappedHandlers = rl.nestHandlerInsideRateLimitingByExactHandler(wrappedHandlers, deploymentHandler)
        }

        val pathHandler = Handlers.path(Handlers.redirect(cfg.solrContextPath))
                .addPrefixPath(cfg.solrContextPath, wrappedHandlers)

        return ServletDeploymentAndHandler(deploymentManager, pathHandler)
    }

    private class RequestLimitHelper(private val rlCfg: RequestLimitConfig) {
        private val requestLimit = RequestLimit(rlCfg.concurrentRequestLimit, rlCfg.maxQueuedRequestLimit)
        private val exactPredicate = Predicates.paths(*rlCfg.exactPaths.toTypedArray())
        private val suffixPredicate = Predicates.suffixes(*rlCfg.pathSuffixes.toTypedArray())

        private fun hasExactPaths(): Boolean = rlCfg.exactPaths.isNotEmpty()
        private fun hasSuffixes(): Boolean = rlCfg.pathSuffixes.isNotEmpty()

        fun nestHandlerInsideRateLimitingBySuffixHandler(handlerToNest: HttpHandler, handlerToRateLimit: HttpHandler): HttpHandler {
            return if (hasSuffixes()) Handlers.predicate(suffixPredicate, Handlers.requestLimitingHandler(requestLimit, handlerToRateLimit), handlerToNest)
            else handlerToNest
        }

        fun nestHandlerInsideRateLimitingByExactHandler(handlerToNest: HttpHandler, handlerToRateLimit: HttpHandler): HttpHandler {
            return if (hasExactPaths()) Handlers.predicate(exactPredicate, Handlers.requestLimitingHandler(requestLimit, handlerToRateLimit), handlerToNest)
            else handlerToNest
        }
    }
}

// TODO:  Undertow says "This class is deprecated, the default servlet should be configured via context params." but not sure what they mean as the alternative
//        given we are embedded, so think we are stuck with this model.

public class SolrDefaultServletConfig() : DefaultServletConfig() {
    private val defaultAllowed: Boolean = false
    private val allowed = setOf("ico", "swf", "js", "css", "png", "jpg", "gif", "html", "htm", "txt", "pdf", "svg")
    private val disallowed = setOf("class", "jar", "war", "zip", "xml")

    public override fun isDefaultAllowed(): Boolean {
        return defaultAllowed
    }

    public override fun getAllowed(): Set<String> {
        return allowed
    }

    public override fun getDisallowed(): Set<String> {
        return disallowed
    }
}


