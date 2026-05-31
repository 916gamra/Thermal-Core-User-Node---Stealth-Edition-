package com.example

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class WormholeKtorServer(private val fileSystemRepository: FileSystemRepository) {

    private var serverInstance: NettyApplicationEngine? = null

    fun start(host: String = "0.0.0.0", port: Int = 8080) {
        if (serverInstance != null) {
            SystemLogRepository.log("KTOR_SERVER", "Server already running.")
            return
        }

        SystemLogRepository.log("KTOR_SERVER", "Bootstrapping Ktor Netty server on $host:$port...")

        try {
            serverInstance = embeddedServer(Netty, port = port, host = host) {
                routing {
                    get("/") {
                        SystemLogRepository.log("KTOR_API", "GET / - Node Status Query")
                        val uptimeMs = System.currentTimeMillis() - nodeStartTime
                        val responseHtml = """
                            <html>
                            <head>
                                <title>Wormhole node Client V2</title>
                                <style>
                                    body { font-family: monospace; background-color: #0F1115; color: #FFFFFF; padding: 40px; }
                                    h1 { color: #3B82F6; border-bottom: 2px solid #1E293B; padding-bottom: 10px; }
                                    .panel { background-color: #1C1F26; padding: 20px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.05); }
                                    .accent { color: #3B82F6; }
                                    .success { color: #10B981; }
                                    a { color: #60A5FA; }
                                </style>
                            </head>
                            <body>
                                <h1>WORMHOLE GATEWAY <span class="success">ONLINE</span></h1>
                                <div class="panel">
                                    <p>Node Name: <span class="accent">wormhole-target-node-v2</span></p>
                                    <p>Network status: <span class="success">TAILSCALE ENCRYPTED ROUTEE</span></p>
                                    <p>Uptime: <span class="accent">${uptimeMs / 1000} seconds</span></p>
                                    <p>Available endpoints:</p>
                                    <ul>
                                        <li><a href="/api/files">GET /api/files</a> - Lists root files</li>
                                        <li>GET /api/download?path=... - Pulls raw file bytes</li>
                                        <li>POST /api/upload?path=... - Pushes/saves file bytes</li>
                                    </ul>
                                </div>
                            </body>
                            </html>
                        """.trimIndent()
                        call.respondText(responseHtml, ContentType.Text.Html)
                    }

                    get("/api/files") {
                        val path = call.parameters["path"] ?: ""
                        SystemLogRepository.log("KTOR_API", "GET /api/files - Reading path: '$path'")
                        val files = fileSystemRepository.listFiles(path)
                        val json = buildString {
                            append("[\n")
                            files.joinTo(this, separator = ",\n") { file ->
                                val escapeName = file.name.replace("\"", "\\\"")
                                val escapePath = file.relativePath.replace("\"", "\\\"")
                                """  { "name": "$escapeName", "path": "$escapePath", "isDirectory": ${file.isDirectory}, "size": ${file.size}, "lastModified": ${file.lastModified} }"""
                            }
                            append("\n]")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }

                    get("/api/download") {
                        val path = call.parameters["path"]
                        if (path.isNullOrEmpty()) {
                            SystemLogRepository.log("KTOR_API_ERR", "GET /api/download - Missed path parameter.")
                            call.respondText("Missing 'path' parameter.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                            return@get
                        }
                        SystemLogRepository.log("KTOR_API", "GET /api/download - Sending: $path")
                        val bytes = fileSystemRepository.readFileBytes(path)
                        if (bytes != null) {
                            call.respondBytes(bytes, ContentType.Application.OctetStream)
                        } else {
                            call.respondText("File not found or read error.", ContentType.Text.Plain, HttpStatusCode.NotFound)
                        }
                    }

                    post("/api/upload") {
                        val path = call.parameters["path"]
                        if (path.isNullOrEmpty()) {
                            SystemLogRepository.log("KTOR_API_ERR", "POST /api/upload - Missed path parameter.")
                            call.respondText("Missing 'path' parameter.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                            return@post
                        }
                        SystemLogRepository.log("KTOR_API", "POST /api/upload - Writing to: $path")
                        try {
                            val stream = call.receiveStream()
                            val bos = ByteArrayOutputStream()
                            val buffer = ByteArray(16384)
                            var read: Int
                            while (stream.read(buffer).also { read = it } != -1) {
                                bos.write(buffer, 0, read)
                            }
                            val fileData = bos.toByteArray()
                            val success = fileSystemRepository.writeFileBytes(path, fileData)
                            if (success) {
                                call.respondText("SUCCESS", ContentType.Text.Plain, HttpStatusCode.OK)
                            } else {
                                call.respondText("ERROR writing to disk.", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                            }
                        } catch (e: Exception) {
                            SystemLogRepository.log("KTOR_API_ERR", "POST /api/upload - Exception: ${e.message}")
                            call.respondText(e.message ?: "Server Error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                }
            }

            serverInstance?.start(wait = false)
            SystemLogRepository.log("KTOR_SERVER", "Ktor netty host listening successfully at http://$host:$port")
        } catch (e: Exception) {
            SystemLogRepository.log("KTOR_SERVER_ERR", "Failed launching Netty server: ${e.message}")
        }
    }

    fun stop() {
        if (serverInstance == null) return
        SystemLogRepository.log("KTOR_SERVER", "Stopping Ktor HTTP server...")
        try {
            serverInstance?.stop(1000L, 5000L)
            serverInstance = null
            SystemLogRepository.log("KTOR_SERVER", "Ktor Netty instance disposed.")
        } catch (e: Exception) {
            SystemLogRepository.log("KTOR_SERVER_ERR", "Error stopping Netty: ${e.message}")
        }
    }

    companion object {
        private val nodeStartTime = System.currentTimeMillis()
    }
}
