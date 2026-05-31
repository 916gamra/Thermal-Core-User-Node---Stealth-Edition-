package com.example

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class WormholeFtpServer(private val fileSystemRepository: FileSystemRepository) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var bindIp: String = "127.0.0.1"
    private val activeConnections = mutableListOf<ClientHandler>()

    fun start(host: String, port: Int = 2121) {
        if (isRunning) {
            SystemLogRepository.log("FTP_SERVER", "FTP Server is already running.")
            return
        }

        bindIp = host
        isRunning = true
        SystemLogRepository.log("FTP_SERVER", "Starting FTP server on $host:$port silently...")

        thread(name = "FtpServerMain") {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName(bindIp))
                SystemLogRepository.log("FTP_SERVER", "FTP Server listening on ftp://$bindIp:$port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    SystemLogRepository.log("FTP_SERVER", "New admin FTP connection accepted from: ${socket.inetAddress.hostAddress}")
                    val handler = ClientHandler(socket)
                    synchronized(activeConnections) {
                        activeConnections.add(handler)
                    }
                    thread(name = "FtpHandler-${socket.port}") {
                        handler.run()
                        synchronized(activeConnections) {
                            activeConnections.remove(handler)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    SystemLogRepository.log("FTP_SERVER_ERR", "FTP server error: ${e.message}")
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        SystemLogRepository.log("FTP_SERVER", "Stopping FTP server...")

        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            SystemLogRepository.log("FTP_SERVER_ERR", "Error closing FTP Server socket: ${e.message}")
        }

        synchronized(activeConnections) {
            for (conn in activeConnections) {
                conn.close()
            }
            activeConnections.clear()
        }
        SystemLogRepository.log("FTP_SERVER", "FTP server stopped successfully.")
    }

    private inner class ClientHandler(private val controlSocket: Socket) {
        private var reader: BufferedReader? = null
        private var writer: PrintWriter? = null
        private var currentPath: String = "" // relative to base directory
        private var passiveServer: ServerSocket? = null
        private var representationType = "I" // I = Binary, A = ASCII
        private var userLoggedIn = false

        fun run() {
            try {
                controlSocket.soTimeout = 300000 // 5 minutes timeout
                reader = BufferedReader(InputStreamReader(controlSocket.inputStream, "UTF-8"))
                writer = PrintWriter(controlSocket.outputStream.bufferedWriter(Charsets.UTF_8))

                // Welcome message
                sendReply("220 System Administration Control Server Ready.")

                while (isRunning) {
                    val line = reader?.readLine() ?: break
                    val parts = line.trim().split(" ", limit = 2)
                    val command = parts[0].uppercase(Locale.US)
                    val arg = if (parts.size > 1) parts[1] else ""

                    SystemLogRepository.log("FTP_CMD", "Received: $command $arg")
                    processCommand(command, arg)
                }
            } catch (e: Exception) {
                SystemLogRepository.log("FTP_CON_ERR", "Connection handler exception: ${e.message}")
            } finally {
                close()
            }
        }

        private fun processCommand(command: String, arg: String) {
            when (command) {
                "USER" -> {
                    userLoggedIn = true // allow login automagically for ease of remote admin
                    sendReply("331 User name okay, password can be anything.")
                }
                "PASS" -> {
                    sendReply("230 User logged in, proceed.")
                }
                "SYST" -> {
                    sendReply("215 UNIX Type: L8")
                }
                "FEAT" -> {
                    writer?.print("211-Features:\r\n UTF8\r\n211 End\r\n")
                    writer?.flush()
                }
                "OPTS" -> {
                    if (arg.uppercase(Locale.US).startsWith("UTF8 ON")) {
                        sendReply("200 UTF8 Option globally enabled.")
                    } else {
                        sendReply("200 Option recognized.")
                    }
                }
                "PWD" -> {
                    val quotePath = if (currentPath.isEmpty()) "/" else "/$currentPath"
                    sendReply("257 \"$quotePath\" is current directory.")
                }
                "TYPE" -> {
                    representationType = arg.uppercase(Locale.US)
                    sendReply("200 Type set to $representationType.")
                }
                "CWD" -> {
                    var target = arg
                    if (target.startsWith("/")) {
                        currentPath = if (target == "/") "" else target.substring(1)
                        sendReply("250 Directory successfully changed to /$currentPath")
                    } else {
                        val checkPath = if (currentPath.isEmpty()) target else "$currentPath/$target"
                        // Clean double slashes
                        val normalized = cleanPath(checkPath)
                        // Verify dir exists
                        val items = fileSystemRepository.listFiles(normalized)
                        currentPath = normalized
                        sendReply("250 Directory changed to /$currentPath")
                    }
                }
                "CDUP" -> {
                    val rootFile = fileSystemRepository.getRootDirectory()
                    val parentFile = File(rootFile, currentPath).parentFile
                    currentPath = if (parentFile == null || parentFile.absolutePath == rootFile.absolutePath || !parentFile.absolutePath.startsWith(rootFile.absolutePath)) {
                        ""
                    } else {
                        parentFile.relativeTo(rootFile).path
                    }
                    sendReply("250 Directory changed up to /$currentPath")
                }
                "PASV" -> {
                    try {
                        passiveServer?.close()
                        // Bind passive connection on same IP interface on an ephemeral port
                        val passSock = ServerSocket(0, 1, InetAddress.getByName(bindIp))
                        passiveServer = passSock
                        val localPort = passSock.localPort
                        val p1 = localPort / 256
                        val p2 = localPort % 256
                        val ipCommas = bindIp.replace('.', ',')
                        sendReply("227 Entering Passive Mode ($ipCommas,$p1,$p2)")
                    } catch (e: Exception) {
                        SystemLogRepository.log("FTP_PASV_ERR", "Failed setting up PASV: ${e.message}")
                        sendReply("425 Can't open data connection.")
                    }
                }
                "PORT" -> {
                    sendReply("502 Command active port mode not implemented. Please use PASV.")
                }
                "LIST", "NLST" -> {
                    val passSock = passiveServer
                    if (passSock == null) {
                        sendReply("425 Use PASV first.")
                        return
                    }
                    sendReply("150 File status okay; about to open data connection.")
                    try {
                        // Accept a connection on the passive server socket with timeout
                        passSock.soTimeout = 10000
                        val dataSocket = passSock.accept()
                        dataSocket.use { ds ->
                            val out = ds.getOutputStream()
                            val items = fileSystemRepository.listFiles(currentPath)
                            val df = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                            val sb = StringBuilder()
                            
                            for (item in items) {
                                if (item.name == "..") continue
                                val typeChar = if (item.isDirectory) 'd' else '-'
                                val sizeStr = if (item.isDirectory) "4096" else item.size.toString()
                                val dateStr = df.format(Date(item.lastModified))
                                sb.append(String.format("%crwxr-xr-x 1 ftp ftp %8s %12s %s\r\n", typeChar, sizeStr, dateStr, item.name))
                            }
                            out.write(sb.toString().toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        sendReply("226 Closing data connection. Listing transfer complete.")
                    } catch (e: Exception) {
                        SystemLogRepository.log("FTP_LIST_ERR", "Error listing: ${e.message}")
                        sendReply("426 Connection closed; transfer aborted.")
                    } finally {
                        closePassive()
                    }
                }
                "RETR" -> {
                    val passSock = passiveServer
                    if (passSock == null) {
                        sendReply("425 Use PASV first.")
                        return
                    }
                    val pathArg = cleanPath(if (currentPath.isEmpty()) arg else "$currentPath/$arg")
                    val fileBytes = fileSystemRepository.readFileBytes(pathArg)
                    if (fileBytes == null) {
                        sendReply("550 File not found or read error.")
                        closePassive()
                        return
                    }

                    sendReply("150 Opening data connection for download.")
                    try {
                        passSock.soTimeout = 15000
                        val dataSocket = passSock.accept()
                        dataSocket.use { ds ->
                            val out = ds.getOutputStream()
                            out.write(fileBytes)
                            out.flush()
                        }
                        sendReply("226 Transfer complete.")
                    } catch (e: Exception) {
                        SystemLogRepository.log("FTP_RETR_ERR", "Error transmitting file: ${e.message}")
                        sendReply("426 Connection aborted.")
                    } finally {
                        closePassive()
                    }
                }
                "STOR" -> {
                    val passSock = passiveServer
                    if (passSock == null) {
                        sendReply("425 Use PASV first.")
                        return
                    }
                    val pathArg = cleanPath(if (currentPath.isEmpty()) arg else "$currentPath/$arg")
                    sendReply("150 Ok to send data.")
                    try {
                        passSock.soTimeout = 20000
                        val dataSocket = passSock.accept()
                        dataSocket.use { ds ->
                            val inputStream = ds.getInputStream()
                            val data = inputStream.readBytes()
                            val success = fileSystemRepository.writeFileBytes(pathArg, data)
                            if (success) {
                                sendReply("226 File received and successfully stored.")
                            } else {
                                sendReply("550 Write to local storage failed.")
                            }
                        }
                    } catch (e: Exception) {
                        SystemLogRepository.log("FTP_STOR_ERR", "Error receiving file: ${e.message}")
                        sendReply("426 Receive aborted.")
                    } finally {
                        closePassive()
                    }
                }
                "DELE" -> {
                    val pathArg = cleanPath(if (currentPath.isEmpty()) arg else "$currentPath/$arg")
                    val success = fileSystemRepository.deleteFile(pathArg)
                    if (success) {
                        sendReply("250 File deleted successfully.")
                    } else {
                        sendReply("550 Delete failed.")
                    }
                }
                "QUIT" -> {
                    sendReply("221 Control connection closed by server.")
                    close()
                }
                "NOOP" -> {
                    sendReply("200 Ok.")
                }
                else -> {
                    sendReply("502 Command not implemented.")
                }
            }
        }

        private fun sendReply(message: String) {
            writer?.print("$message\r\n")
            writer?.flush()
        }

        private fun cleanPath(p: String): String {
            var temp = p.replace("//", "/")
            if (temp.startsWith("/")) {
                temp = temp.substring(1)
            }
            if (temp.endsWith("/")) {
                temp = temp.substring(0, temp.length - 1)
            }
            return temp
        }

        private fun closePassive() {
            try {
                passiveServer?.close()
            } catch (e: Exception) {
            }
            passiveServer = null
        }

        fun close() {
            closePassive()
            try {
                reader?.close()
            } catch (e: Exception) {}
            try {
                writer?.close()
            } catch (e: Exception) {}
            try {
                controlSocket.close()
            } catch (e: Exception) {}
        }
    }
}
