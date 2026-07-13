package com.example

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val serverRunning by viewModel.serverRunning.collectAsStateWithLifecycle()
    val serverPort by viewModel.port.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val pageTitle by viewModel.pageTitle.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(0) }
    var urlInputText by remember { mutableStateOf(currentUrl) }

    // Keep WebView reference
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Sync input box and WebView when URL changes (internally, via Deep Link, or API)
    LaunchedEffect(currentUrl, webViewInstance) {
        if (currentUrl != urlInputText) {
            urlInputText = currentUrl
        }
        val webView = webViewInstance
        if (webView != null) {
            val formatted = formatUrl(currentUrl)
            if (webView.url != formatted && webView.url != currentUrl) {
                webView.loadUrl(formatted)
            }
        }
    }

    // Define BrowserCommandHandler implementation
    val commandHandler = remember(webViewInstance) {
        object : BrowserCommandHandler {
            override suspend fun handleCommand(command: BrowserCommand): CommandResult = withContext(Dispatchers.Main) {
                try {
                    val webView = webViewInstance ?: return@withContext CommandResult.Error("WebView is not ready")
                    when (command) {
                        is BrowserCommand.LoadUrl -> {
                            val formatted = formatUrl(command.url)
                            viewModel.updateUrl(formatted)
                            webView.loadUrl(formatted)
                            CommandResult.Success("Successfully navigated to $formatted")
                        }
                        is BrowserCommand.GoBack -> {
                            if (webView.canGoBack()) {
                                webView.goBack()
                                CommandResult.Success("Went back in history")
                            } else {
                                CommandResult.Error("Cannot go back: no history")
                            }
                        }
                        is BrowserCommand.GoForward -> {
                            if (webView.canGoForward()) {
                                webView.goForward()
                                CommandResult.Success("Went forward in history")
                            } else {
                                CommandResult.Error("Cannot go forward: no history")
                            }
                        }
                        is BrowserCommand.Reload -> {
                            webView.reload()
                            CommandResult.Success("Page reloaded")
                        }
                        is BrowserCommand.ExecuteJavaScript -> {
                            val deferred = CompletableDeferred<String>()
                            webView.evaluateJavascript(command.script) { result ->
                                deferred.complete(result ?: "null")
                            }
                            val resultVal = deferred.await()
                            CommandResult.Success("JavaScript executed successfully", data = resultVal)
                        }
                        is BrowserCommand.GetPageSource -> {
                            val deferred = CompletableDeferred<String>()
                            webView.evaluateJavascript("document.documentElement.outerHTML") { rawResult ->
                                val unescaped = unescapeJsonString(rawResult ?: "null")
                                deferred.complete(unescaped)
                            }
                            val html = deferred.await()
                            CommandResult.Success("Page source retrieved", data = html)
                        }
                        is BrowserCommand.CaptureScreenshot -> {
                            val width = webView.width.coerceAtLeast(1)
                            val height = webView.height.coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            webView.draw(canvas)
                            
                            val outputStream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                            bitmap.recycle()
                            val bytes = outputStream.toByteArray()
                            CommandResult.Success("Screenshot captured", data = bytes)
                        }
                        is BrowserCommand.GetStatus -> {
                            val statusJson = org.json.JSONObject().apply {
                                put("url", webView.url ?: currentUrl)
                                put("title", webView.title ?: pageTitle)
                                put("loading", webView.progress != 100)
                            }
                            CommandResult.Success("Status retrieved", data = statusJson.toString())
                        }
                    }
                } catch (e: Exception) {
                    CommandResult.Error(e.message ?: "Unknown command execution error")
                }
            }
        }
    }

    // Auto-start server initially if needed
    LaunchedEffect(webViewInstance) {
        if (webViewInstance != null && !serverRunning) {
            viewModel.startServer(commandHandler)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                // Header Banner
                CenterAlignedTopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Termux Link",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Termux Web Link",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )

                // Navigation Tabs
                TabRow(selectedTabIndex = activeTab) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Browser")
                            }
                        }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Termux Link")
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (activeTab == 0) {
                // Browser Tab Content
                Column(modifier = Modifier.fillMaxSize()) {
                    // Url Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = urlInputText,
                            onValueChange = { urlInputText = it },
                            placeholder = { Text("Search or enter web address") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (urlInputText.startsWith("https")) Icons.Default.Lock else Icons.Default.Language,
                                    contentDescription = null,
                                    tint = if (urlInputText.startsWith("https")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                if (urlInputText.isNotEmpty()) {
                                    IconButton(onClick = { urlInputText = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    focusManager.clearFocus()
                                    viewModel.updateUrl(urlInputText)
                                    coroutineScope.launch {
                                        commandHandler.handleCommand(BrowserCommand.LoadUrl(urlInputText))
                                    }
                                }
                            ),
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .testTag("url_input_field")
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.updateUrl(urlInputText)
                                coroutineScope.launch {
                                    commandHandler.handleCommand(BrowserCommand.LoadUrl(urlInputText))
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp))
                                .testTag("navigate_button")
                        ) {
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = "Go",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Linear Progress Bar for Loading State
                    AnimatedVisibility(
                        visible = isLoading,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }

                    // WebView Component
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    
                                    // Robust WebView configuration
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            viewModel.updateLoading(true)
                                            url?.let { viewModel.updateUrl(it) }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            viewModel.updateLoading(false)
                                            url?.let { viewModel.updateUrl(it) }
                                            view?.title?.let { viewModel.updateTitle(it) }
                                        }
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onReceivedTitle(view: WebView?, title: String?) {
                                            super.onReceivedTitle(view, title)
                                            title?.let { viewModel.updateTitle(it) }
                                        }
                                    }

                                    webViewInstance = this
                                    loadUrl(currentUrl)
                                }
                            },
                            update = { webView ->
                                // Note: The actual loading/navigation happens via controller to avoid infinite recomposition issues
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Bottom Navigation / Browser Control Panel
                    Surface(
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(vertical = 8.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { webViewInstance?.goBack() },
                                enabled = webViewInstance?.canGoBack() == true,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }

                            IconButton(
                                onClick = { webViewInstance?.goForward() },
                                enabled = webViewInstance?.canGoForward() == true,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "Forward")
                            }

                            IconButton(
                                onClick = { webViewInstance?.reload() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload")
                            }

                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        commandHandler.handleCommand(BrowserCommand.LoadUrl("https://google.com"))
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Home, contentDescription = "Home")
                            }

                            // Simple visual indicator of control server status on the browser tab
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (serverRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable { activeTab = 1 }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (serverRunning) Color(0xFF4CAF50) else Color(0xFFF44336))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (serverRunning) "LINK ON" else "LINK OFF",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (serverRunning) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                        }
                    }
                }
            } else {
                // Termux Integration & Configuration Tab
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Direct Termux Link Launcher Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "Termux App Direct Link",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    "This app is now directly linked with Termux! Any web link you open or share in Termux (using termux-open or browser option) can be viewed directly in this browser.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Button(
                                    onClick = {
                                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                                        if (launchIntent != null) {
                                            context.startActivity(launchIntent)
                                        } else {
                                            Toast.makeText(context, "Termux application is not installed on this device!", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("launch_termux_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open Termux App")
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = "💡 Tip: Setup Termux open-url default to this app, then simply type `termux-open <url>` to launch directly inside this window.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Server Control Panel Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        Text(
                                            "Local Control Server",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (serverRunning) "Active on port $serverPort" else "Inactive",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (serverRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Switch(
                                        checked = serverRunning,
                                        onCheckedChange = { isChecked ->
                                            if (isChecked) {
                                                viewModel.startServer(commandHandler)
                                            } else {
                                                viewModel.stopServer()
                                            }
                                        },
                                        modifier = Modifier.testTag("server_status_switch")
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(modifier = Modifier.height(12.dp))

                                // Port setting
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Server Port Number",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    
                                    var portText by remember { mutableStateOf(serverPort.toString()) }
                                    OutlinedTextField(
                                        value = portText,
                                        onValueChange = { input ->
                                            if (input.all { it.isDigit() } && input.length <= 5) {
                                                portText = input
                                                val parsed = input.toIntOrNull()
                                                if (parsed != null && parsed in 1024..65535) {
                                                    viewModel.setPort(parsed)
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .width(100.dp)
                                            .height(52.dp),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = !serverRunning
                                    )
                                }
                            }
                        }
                    }

                    // CLI Connection Command Snippets Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Termux Control Commands",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Run these commands inside Termux to remote-control this browser:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // One-Click CLI Installer Card
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "🚀 One-Click CLI Tool Installer",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Copy and run this command in Termux to install a global 'tb' tool for full browser control:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF1E222B), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val installerCommand = "curl -s http://localhost:$serverPort/tb > tb && chmod +x tb && mv tb ~/../usr/bin/"
                                            Text(
                                                text = installerCommand,
                                                color = Color(0xFF61AFEF),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = ClipData.newPlainText("termux_installer", installerCommand)
                                                    clipboard.setPrimaryClip(clip)
                                                    Toast.makeText(context, "Installer command copied!", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy installer",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "💡 Setup once, then type 'tb help' to see all commands (open, back, reload, scroll, type, click, screenshot, etc.).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                // List of snippets
                                CommandSnippetItem(
                                    title = "1. Check Connection Status",
                                    desc = "Fetch browser's current active URL and state.",
                                    command = "curl http://localhost:$serverPort/api/status",
                                    context = context
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                CommandSnippetItem(
                                    title = "2. Remote Navigate to URL",
                                    desc = "Directly load a website in the browser window.",
                                    command = "curl -X POST -H \"Content-Type: application/json\" -d '{\"url\":\"https://github.com\"}' http://localhost:$serverPort/api/navigate",
                                    context = context
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                CommandSnippetItem(
                                    title = "3. Execute JavaScript code",
                                    desc = "Run DOM scripts and receive outputs instantly.",
                                    command = "curl -X POST -H \"Content-Type: application/json\" -d '{\"script\":\"document.title\"}' http://localhost:$serverPort/api/eval",
                                    context = context
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                CommandSnippetItem(
                                    title = "4. Take Live Page Screenshot",
                                    desc = "Get WebView rendered screenshot directly saved to your Termux terminal.",
                                    command = "curl -o shot.png http://localhost:$serverPort/api/screenshot",
                                    context = context
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                CommandSnippetItem(
                                    title = "5. Extract Full Page HTML",
                                    desc = "Grab the live page source HTML.",
                                    command = "curl http://localhost:$serverPort/api/html",
                                    context = context
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                CommandSnippetItem(
                                    title = "6. Browser Control Operations",
                                    desc = "Send UI keys or navigation events ('back', 'forward', 'reload').",
                                    command = "curl -X POST -H \"Content-Type: application/json\" -d '{\"action\":\"back\"}' http://localhost:$serverPort/api/control",
                                    context = context
                                )
                            }
                        }
                    }

                    // Terminal Console Logs Card
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF1E1E1E) // Slate dark theme
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Terminal,
                                            contentDescription = null,
                                            tint = Color(0xFF00FF00),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Terminal API Console Logs",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    TextButton(
                                        onClick = { viewModel.clearLogs() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))
                                    ) {
                                        Text("Clear", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Divider(color = Color(0xFF333333))
                                Spacer(modifier = Modifier.height(6.dp))

                                // Log output console
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF121212), RoundedCornerShape(4.dp))
                                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(4.dp))
                                        .padding(8.dp)
                                ) {
                                    if (logs.isEmpty()) {
                                        Text(
                                            "No API traffic recorded. Run a curl command in Termux to start controlling.",
                                            color = Color(0xFF888888),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            reverseLayout = false
                                        ) {
                                            items(logs) { entry ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "[${entry.timestamp}]",
                                                        color = Color(0xFF888888),
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = entry.message,
                                                        color = if (entry.message.contains("→")) Color(0xFF81D4FA) else if (entry.message.contains("✓")) Color(0xFFC8E6C9) else Color(0xFFFFCC80),
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommandSnippetItem(
    title: String,
    desc: String,
    command: String,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF282C34), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF1E222B), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = command,
                color = Color(0xFF98C379),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("termux_command", command)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy command",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Utility to cleanly unescape javascript evaluation results
fun unescapeJsonString(jsonStr: String): String {
    if (jsonStr == "null") return ""
    var str = jsonStr
    // Remove enclosing double-quotes if they exist
    if (str.startsWith("\"") && str.endsWith("\"") && str.length >= 2) {
        str = str.substring(1, str.length - 1)
    }
    // Replace unescaped sequences
    return str
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
}

// Ensure URL contains scheme (http or https), or search google
fun formatUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "https://google.com"
    
    // Check if it's already a full URL
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://")) {
        return trimmed
    }
    
    // Check if it looks like a domain name
    val domainRegex = "^([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(/.*)?$".toRegex()
    val localhostRegex = "^localhost(:[0-9]+)?(/.*)?$".toRegex()
    val ipRegex = "^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(:[0-9]+)?(/.*)?$".toRegex()
    
    if (domainRegex.matches(trimmed) || localhostRegex.matches(trimmed) || ipRegex.matches(trimmed)) {
        return "https://$trimmed"
    }
    
    // Otherwise, treat as search query on Google
    return try {
        "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
    } catch (e: Exception) {
        "https://www.google.com/search?q=" + trimmed
    }
}
