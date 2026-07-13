package com.example

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import org.json.JSONObject

class ControlServer(
    private val port: Int,
    private val commandHandler: BrowserCommandHandler,
    private val onLog: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        serverScope.launch {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                onLog("Server started on localhost:$port")
                while (isRunning) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    launch {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onLog("Server error: ${e.message}")
                }
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignored
        }
        serverSocket = null
        onLog("Server stopped")
    }

    private suspend fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()
            
            // Read request line
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            onLog("→ $method $path")

            // Read headers
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // Read body if content length specified
            val body = if (contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(charBuffer, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                String(charBuffer)
            } else ""

            // Route request
            routeRequest(method, path, body, output)
        } catch (e: Exception) {
            if (e !is SocketException) {
                onLog("Error handling client: ${e.message}")
            }
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // ignored
            }
        }
    }

    private suspend fun routeRequest(method: String, path: String, body: String, output: OutputStream) {
        try {
            when {
                path == "/api/status" && method == "GET" -> {
                    val result = commandHandler.handleCommand(BrowserCommand.GetStatus)
                    when (result) {
                        is CommandResult.Success -> {
                            val data = result.data as? String ?: "{}"
                            sendJsonResponse(output, 200, data)
                        }
                        is CommandResult.Error -> {
                            val res = JSONObject().apply { put("error", result.message) }
                            sendJsonResponse(output, 500, res.toString())
                        }
                    }
                }
                path == "/api/navigate" && method == "POST" -> {
                    val url = try {
                        JSONObject(body).getString("url")
                    } catch (e: Exception) {
                        ""
                    }
                    if (url.isNotEmpty()) {
                        val result = commandHandler.handleCommand(BrowserCommand.LoadUrl(url))
                        when (result) {
                            is CommandResult.Success -> {
                                val res = JSONObject().apply {
                                    put("success", true)
                                    put("message", result.message)
                                }
                                onLog("✓ Navigate: $url")
                                sendJsonResponse(output, 200, res.toString())
                            }
                            is CommandResult.Error -> {
                                val res = JSONObject().apply {
                                    put("success", false)
                                    put("error", result.message)
                                }
                                onLog("✗ Navigate error: ${result.message}")
                                sendJsonResponse(output, 500, res.toString())
                            }
                        }
                    } else {
                        val res = JSONObject().apply {
                            put("success", false)
                            put("error", "Missing 'url' in body JSON")
                        }
                        onLog("✗ Navigate error: missing url")
                        sendJsonResponse(output, 400, res.toString())
                    }
                }
                path == "/api/eval" && method == "POST" -> {
                    val script = try {
                        JSONObject(body).getString("script")
                    } catch (e: Exception) {
                        ""
                    }
                    if (script.isNotEmpty()) {
                        val result = commandHandler.handleCommand(BrowserCommand.ExecuteJavaScript(script))
                        when (result) {
                            is CommandResult.Success -> {
                                val jsResult = result.data as? String ?: "null"
                                val res = JSONObject().apply {
                                    put("success", true)
                                    put("result", jsResult)
                                }
                                onLog("✓ JS Eval: $script → $jsResult")
                                sendJsonResponse(output, 200, res.toString())
                            }
                            is CommandResult.Error -> {
                                val res = JSONObject().apply {
                                    put("success", false)
                                    put("error", result.message)
                                }
                                onLog("✗ JS Eval error: ${result.message}")
                                sendJsonResponse(output, 500, res.toString())
                            }
                        }
                    } else {
                        val res = JSONObject().apply {
                            put("success", false)
                            put("error", "Missing 'script' in body JSON")
                        }
                        onLog("✗ JS Eval error: missing script")
                        sendJsonResponse(output, 400, res.toString())
                    }
                }
                path == "/api/control" && method == "POST" -> {
                    val action = try {
                        JSONObject(body).getString("action")
                    } catch (e: Exception) {
                        ""
                    }
                    if (action.isNotEmpty()) {
                        val cmd = when (action.lowercase()) {
                            "back" -> BrowserCommand.GoBack
                            "forward" -> BrowserCommand.GoForward
                            "reload", "refresh" -> BrowserCommand.Reload
                            else -> null
                        }
                        if (cmd != null) {
                            val result = commandHandler.handleCommand(cmd)
                            when (result) {
                                is CommandResult.Success -> {
                                    val res = JSONObject().apply {
                                        put("success", true)
                                        put("message", result.message)
                                    }
                                    onLog("✓ Control Action: $action")
                                    sendJsonResponse(output, 200, res.toString())
                                }
                                is CommandResult.Error -> {
                                    val res = JSONObject().apply {
                                        put("success", false)
                                        put("error", result.message)
                                    }
                                    onLog("✗ Control Action error: ${result.message}")
                                    sendJsonResponse(output, 500, res.toString())
                                }
                            }
                        } else {
                            val res = JSONObject().apply {
                                put("success", false)
                                put("error", "Unsupported action: $action")
                            }
                            onLog("✗ Control Action error: unsupported action $action")
                            sendJsonResponse(output, 400, res.toString())
                        }
                    } else {
                        val res = JSONObject().apply {
                            put("success", false)
                            put("error", "Missing 'action' in body JSON")
                        }
                        onLog("✗ Control error: missing action")
                        sendJsonResponse(output, 400, res.toString())
                    }
                }
                path == "/api/html" && method == "GET" -> {
                    val result = commandHandler.handleCommand(BrowserCommand.GetPageSource)
                    when (result) {
                        is CommandResult.Success -> {
                            val html = result.data as? String ?: ""
                            onLog("✓ Fetched Page HTML")
                            sendTextResponse(output, 200, "text/html; charset=utf-8", html)
                        }
                        is CommandResult.Error -> {
                            val res = JSONObject().apply { put("error", result.message) }
                            sendJsonResponse(output, 500, res.toString())
                        }
                    }
                }
                path == "/api/screenshot" && method == "GET" -> {
                    val result = commandHandler.handleCommand(BrowserCommand.CaptureScreenshot)
                    when (result) {
                        is CommandResult.Success -> {
                            val bytes = result.data as? ByteArray
                            if (bytes != null) {
                                onLog("✓ Captured Screenshot")
                                sendBytesResponse(output, 200, "image/png", bytes)
                            } else {
                                val res = JSONObject().apply { put("error", "Empty screenshot bytes") }
                                sendJsonResponse(output, 500, res.toString())
                            }
                        }
                        is CommandResult.Error -> {
                            val res = JSONObject().apply { put("error", result.message) }
                            onLog("✗ Screenshot failed: ${result.message}")
                            sendJsonResponse(output, 500, res.toString())
                        }
                    }
                }
                else -> {
                    val res = JSONObject().apply { put("error", "Route not found") }
                    onLog("✗ Route not found: $method $path")
                    sendJsonResponse(output, 404, res.toString())
                }
            }
        } catch (e: Exception) {
            onLog("Error processing response: ${e.message}")
        }
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, body: String) {
        sendBytesResponse(output, statusCode, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    }

    private fun sendTextResponse(output: OutputStream, statusCode: Int, contentType: String, body: String) {
        sendBytesResponse(output, statusCode, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private fun sendBytesResponse(output: OutputStream, statusCode: Int, contentType: String, data: ByteArray) {
        val statusMsg = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
        val headers = "HTTP/1.1 $statusCode $statusMsg\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(data)
        output.flush()
    }
}
