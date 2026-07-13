package com.example

sealed interface BrowserCommand {
    data class LoadUrl(val url: String) : BrowserCommand
    object GoBack : BrowserCommand
    object GoForward : BrowserCommand
    object Reload : BrowserCommand
    data class ExecuteJavaScript(val script: String) : BrowserCommand
    object GetPageSource : BrowserCommand
    object CaptureScreenshot : BrowserCommand
    object GetStatus : BrowserCommand
}

sealed interface CommandResult {
    data class Success(val message: String, val data: Any? = null) : CommandResult
    data class Error(val message: String) : CommandResult
}

interface BrowserCommandHandler {
    suspend fun handleCommand(command: BrowserCommand): CommandResult
}
