package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrowserViewModel : ViewModel() {

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _port = MutableStateFlow(8080)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _currentUrl = MutableStateFlow("https://google.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("Google")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var controlServer: ControlServer? = null

    data class LogEntry(
        val timestamp: String,
        val message: String
    )

    fun startServer(commandHandler: BrowserCommandHandler) {
        if (_serverRunning.value) return
        
        val currentPort = _port.value
        controlServer = ControlServer(currentPort, commandHandler) { message ->
            addLog(message)
        }
        controlServer?.start()
        _serverRunning.value = true
        addLog("Server successfully initialized on port $currentPort")
    }

    fun stopServer() {
        if (!_serverRunning.value) return
        controlServer?.stop()
        controlServer = null
        _serverRunning.value = false
        addLog("Server stopped successfully")
    }

    fun setPort(newPort: Int) {
        if (_serverRunning.value) {
            addLog("Please stop the server before changing the port.")
            return
        }
        if (newPort in 1024..65535) {
            _port.value = newPort
            addLog("Port set to $newPort")
        } else {
            addLog("Invalid port. Must be between 1024 and 65535.")
        }
    }

    fun addLog(message: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = sdf.format(Date())
        viewModelScope.launch {
            _logs.update { currentList ->
                val updated = currentList.toMutableList()
                updated.add(0, LogEntry(timestamp, message)) // Add new log to the top
                if (updated.size > 150) {
                    updated.removeAt(updated.lastIndex) // Keep logs under 150 entries
                }
                updated
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Console logs cleared")
    }

    fun updateUrl(url: String) {
        _currentUrl.value = url
    }

    fun updateTitle(title: String) {
        _pageTitle.value = title
    }

    fun updateLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    override fun onCleared() {
        super.onCleared()
        stopServer()
    }
}
