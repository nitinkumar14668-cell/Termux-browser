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
                (path == "/tb" || path == "/tb.sh" || path == "/cli") && method == "GET" -> {
                    val script = getCliScript(port)
                    sendTextResponse(output, 200, "text/plain; charset=utf-8", script)
                }
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

    private fun getCliScript(currentPort: Int): String {
        val template = """
#!/bin/bash

# Termux Browser (tb) CLI Client
# Fully control your browser from Termux terminal.

PORT=__PORT__
BASE_URL="http://localhost:_DS_PORT"

# Colors for terminal
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

show_help() {
    echo -e "_DS_{GREEN}=== Termux Browser (tb) CLI Client ===_DS_{NC}"
    echo -e "Control your Android browser window directly from Termux.\n"
    echo -e "_DS_{YELLOW}Usage:_DS_{NC}"
    echo -e "  tb [command] [args...]\n"
    echo -e "_DS_{YELLOW}Commands:_DS_{NC}"
    echo -e "  _DS_{CYAN}status_DS_{NC}                    Show browser status (active URL, Title, and load state)"
    echo -e "  _DS_{CYAN}open | go | navigate_DS_{NC} <url>  Open a website or query (performs Google Search if query)"
    echo -e "  _DS_{CYAN}back | b_DS_{NC}                  Go back in history"
    echo -e "  _DS_{CYAN}forward | f_DS_{NC}               Go forward in history"
    echo -e "  _DS_{CYAN}reload | r_DS_{NC}                Reload the active webpage"
    echo -e "  _DS_{CYAN}title_DS_{NC}                     Get current webpage title"
    echo -e "  _DS_{CYAN}url_DS_{NC}                       Get current webpage URL"
    echo -e "  _DS_{CYAN}html | source_DS_{NC}             Dump raw HTML source of the active webpage"
    echo -e "  _DS_{CYAN}dump | text_DS_{NC}               Extract and dump visible text from the page"
    echo -e "  _DS_{CYAN}screenshot | shot_DS_{NC} [file]   Take a screenshot and save it (default: screenshot.png)"
    echo -e "  _DS_{CYAN}scroll_DS_{NC} <up|down|top|bottom> Scroll the webpage layout"
    echo -e "  _DS_{CYAN}click_DS_{NC} <css-selector>      Click any HTML element matching CSS selector"
    echo -e "  _DS_{CYAN}type_DS_{NC} <selector> <text>    Fill an input field with text"
    echo -e "  _DS_{CYAN}eval | js_DS_{NC} "<code>"        Evaluate raw JavaScript inside the webpage context"
    echo -e "  _DS_{CYAN}help | -h | --help_DS_{NC}        Show this help screen\n"
    echo -e "_DS_{YELLOW}Examples:_DS_{NC}"
    echo -e "  tb open google.com"
    echo -e "  tb open \"how to install termux-api\" (Google Search)"
    echo -e "  tb scroll down"
    echo -e "  tb click \"#submit-button\""
    echo -e "  tb type \"input[type='text']\" \"termux user\""
    echo -e "  tb screenshot test.png"
}

if ! command -v curl &> /dev/null; then
    echo -e "_DS_{RED}Error: curl is required to run this script. Run 'pkg install curl' first._DS_{NC}"
    exit 1
fi

CMD=_DS_1
shift

case "_DS_CMD" in
    "status")
        res=_DS_(curl -s "_DS_BASE_URL/api/status")
        if [ _DS_? -ne 0 ] || [ -z "_DS_res" ]; then
            echo -e "_DS_{RED}Error: Could not connect to Termux Browser at _DS_BASE_URL. Is the Local Control Server running?_DS_{NC}"
            exit 1
        fi
        url=_DS_(echo "_DS_res" | grep -o '"url":"[^"]*' | cut -d'"' -f4)
        title=_DS_(echo "_DS_res" | grep -o '"title":"[^"]*' | cut -d'"' -f4)
        loading=_DS_(echo "_DS_res" | grep -o '"loading":[^,}]*' | cut -d':' -f2)
        
        echo -e "_DS_{GREEN}Browser Status:_DS_{NC}"
        echo -e "  _DS_{YELLOW}Title:_DS_{NC}   _DS_title"
        echo -e "  _DS_{YELLOW}URL:_DS_{NC}     _DS_url"
        if [ "_DS_loading" = "true" ]; then
            echo -e "  _DS_{YELLOW}State:_DS_{NC}   _DS_{RED}Loading..._DS_{NC}"
        else
            echo -e "  _DS_{YELLOW}State:_DS_{NC}   _DS_{GREEN}Ready / Idle_DS_{NC}"
        fi
        ;;
    "open" | "go" | "navigate")
        TARGET_URL="_DS_*"
        if [ -z "_DS_TARGET_URL" ]; then
            echo -e "_DS_{RED}Error: URL or search query is required._DS_{NC}"
            exit 1
        fi
        
        if [[ "_DS_TARGET_URL" != *.* ]] || [[ "_DS_TARGET_URL" == *" "* ]]; then
            encoded_query=_DS_(echo "_DS_TARGET_URL" | sed 's/ /+/g')
            TARGET_URL="https://www.google.com/search?q=_DS_encoded_query"
        elif [[ "_DS_TARGET_URL" != http://* ]] && [[ "_DS_TARGET_URL" != https://* ]]; then
            TARGET_URL="https://_DS_TARGET_URL"
        fi
        
        echo -e "_DS_{YELLOW}Navigating to:_DS_{NC} _DS_TARGET_URL"
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d "{\"url\":\"_DS_TARGET_URL\"}" "_DS_BASE_URL/api/navigate")
        echo "_DS_res" | grep -q '"success":true' && echo -e "_DS_{GREEN}Success!_DS_{NC}" || echo -e "_DS_{RED}Failed: _DS_res_DS_{NC}"
        ;;
    "back" | "b")
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d '{"action":"back"}' "_DS_BASE_URL/api/control")
        echo "_DS_res" | grep -q '"success":true' && echo -e "_DS_{GREEN}Went Back._DS_{NC}" || echo -e "_DS_{RED}Failed: _DS_res_DS_{NC}"
        ;;
    "forward" | "f")
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d '{"action":"forward"}' "_DS_BASE_URL/api/control")
        echo "_DS_res" | grep -q '"success":true' && echo -e "_DS_{GREEN}Went Forward._DS_{NC}" || echo -e "_DS_{RED}Failed: _DS_res_DS_{NC}"
        ;;
    "reload" | "r" | "refresh")
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d '{"action":"reload"}' "_DS_BASE_URL/api/control")
        echo "_DS_res" | grep -q '"success":true' && echo -e "_DS_{GREEN}Page Reloaded._DS_{NC}" || echo -e "_DS_{RED}Failed: _DS_res_DS_{NC}"
        ;;
    "title")
        res=_DS_(curl -s "_DS_BASE_URL/api/status")
        title=_DS_(echo "_DS_res" | grep -o '"title":"[^"]*' | cut -d'"' -f4)
        echo "_DS_title"
        ;;
    "url")
        res=_DS_(curl -s "_DS_BASE_URL/api/status")
        url=_DS_(echo "_DS_res" | grep -o '"url":"[^"]*' | cut -d'"' -f4)
        echo "_DS_url"
        ;;
    "html" | "source")
        curl -s "_DS_BASE_URL/api/html"
        ;;
    "dump" | "text")
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d '{"script":"document.body.innerText"}' "_DS_BASE_URL/api/eval")
        echo "_DS_res" | sed -n 's/.*"result":"\(.*\)".*/\1/p' | sed 's/\\n/\n/g' | sed 's/\\"/"/g'
        ;;
    "screenshot" | "shot")
        FILE_NAME=_DS_{1:-"screenshot.png"}
        echo -e "_DS_{YELLOW}Capturing live browser screenshot..._DS_{NC}"
        curl -s -o "_DS_FILE_NAME" "_DS_BASE_URL/api/screenshot"
        if [ _DS_? -eq 0 ] && [ -f "_DS_FILE_NAME" ] && [ -s "_DS_FILE_NAME" ]; then
            echo -e "_DS_{GREEN}Screenshot saved successfully as: _DS_FILE_NAME_DS_{NC}"
        else
            echo -e "_DS_{RED}Failed to capture or save screenshot._DS_{NC}"
        fi
        ;;
    "scroll")
        DIR=_DS_(echo "_DS_1" | tr '[:upper:]' '[:lower:]')
        if [ "_DS_DIR" = "down" ]; then
            JS_CODE="window.scrollBy(0, window.innerHeight * 0.7);"
        elif [ "_DS_DIR" = "up" ]; then
            JS_CODE="window.scrollBy(0, -window.innerHeight * 0.7);"
        elif [ "_DS_DIR" = "top" ]; then
            JS_CODE="window.scrollTo(0, 0);"
        elif [ "_DS_DIR" = "bottom" ]; then
            JS_CODE="window.scrollTo(0, document.body.scrollHeight);"
        else
            echo -e "_DS_{RED}Error: Scroll direction must be up, down, top, or bottom._DS_{NC}"
            exit 1
        fi
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d "{\"script\":\"_DS_JS_CODE\"}" "_DS_BASE_URL/api/eval")
        echo "_DS_res" | grep -q '"success":true' && echo -e "_DS_{GREEN}Scrolled _DS_DIR._DS_{NC}" || echo -e "_DS_{RED}Failed: _DS_res_DS_{NC}"
        ;;
    "click")
        SELECTOR="_DS_1"
        if [ -z "_DS_SELECTOR" ]; then
            echo -e "_DS_{RED}Error: CSS selector required._DS_{NC}"
            exit 1
        fi
        ESCAPED_SELECTOR=_DS_(echo "_DS_SELECTOR" | sed 's/"/\\"/g')
        JS_CODE="const el = document.querySelector(\"_DS_ESCAPED_SELECTOR\"); if (el) { el.click(); 'Clicked'; } else { throw new Error('Element not found'); }"
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d "{\"script\":\"_DS_JS_CODE\"}" "_DS_BASE_URL/api/eval")
        if echo "_DS_res" | grep -q '"success":true'; then
            echo -e "_DS_{GREEN}Successfully clicked: _DS_SELECTOR_DS_{NC}"
        else
            err=_DS_(echo "_DS_res" | grep -o '"error":"[^"]*' | cut -d'"' -f4)
            echo -e "_DS_{RED}Failed to click: _DS_{err:-_DS_res}_DS_{NC}"
        fi
        ;;
    "type")
        SELECTOR="_DS_1"
        TEXT="_DS_2"
        if [ -z "_DS_SELECTOR" ] || [ -z "_DS_TEXT" ]; then
            echo -e "_DS_{RED}Error: Selector and Text are required. Usage: tb type <selector> <text>_DS_{NC}"
            exit 1
        fi
        ESCAPED_SELECTOR=_DS_(echo "_DS_SELECTOR" | sed 's/"/\\"/g')
        ESCAPED_TEXT=_DS_(echo "_DS_TEXT" | sed 's/"/\\"/g')
        JS_CODE="const el = document.querySelector(\"_DS_ESCAPED_SELECTOR\"); if (el) { el.value = \"_DS_ESCAPED_TEXT\"; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); 'Typed'; } else { throw new Error('Input element not found'); }"
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d "{\"script\":\"_DS_JS_CODE\"}" "_DS_BASE_URL/api/eval")
        if echo "_DS_res" | grep -q '"success":true'; then
            echo -e "_DS_{GREEN}Typed text into: _DS_SELECTOR_DS_{NC}"
        else
            err=_DS_(echo "_DS_res" | grep -o '"error":"[^"]*' | cut -d'"' -f4)
            echo -e "_DS_{RED}Failed to type: _DS_{err:-_DS_res}_DS_{NC}"
        fi
        ;;
    "eval" | "js")
        SCRIPT="_DS_*"
        if [ -z "_DS_SCRIPT" ]; then
            echo -e "_DS_{RED}Error: JavaScript code is required._DS_{NC}"
            exit 1
        fi
        ESCAPED_SCRIPT=_DS_(echo "_DS_SCRIPT" | sed 's/\\\\/\\\\\\\\/g' | sed 's/"/\\"/g')
        res=_DS_(curl -s -X POST -H "Content-Type: application/json" -d "{\"script\":\"_DS_ESCAPED_SCRIPT\"}" "_DS_BASE_URL/api/eval")
        if echo "_DS_res" | grep -q '"success":true'; then
            output=_DS_(echo "_DS_res" | sed -n 's/.*"result":"\(.*\)".*/\1/p')
            echo -e "_DS_{GREEN}Result:_DS_{NC} _DS_output"
        else
            err=_DS_(echo "_DS_res" | grep -o '"error":"[^"]*' | cut -d'"' -f4)
            echo -e "_DS_{RED}Error: _DS_{err:-_DS_res}_DS_{NC}"
        fi
        ;;
    *)
        show_help
        ;;
esac
"""
        return template.replace("_DS_", "$").replace("__PORT__", currentPort.toString())
    }
}
