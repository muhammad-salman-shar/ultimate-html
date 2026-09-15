package com.webviewapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Main Activity - WebView Application Entry Point
 * 
 * This activity hosts a WebView that loads index.html from assets.
 * It sets up the JavaScript bridge and handles all native interactions.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var bridge: WebViewBridge
    
    // File picker handling
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    
    // Activity result launchers
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleFilePickerResult(result.resultCode, result.data)
    }
    
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleCameraResult(result.resultCode, result.data)
    }
    
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        handlePermissionLauncherResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_main)
        
        webView = findViewById(R.id.webView)
        
        setupWebView()
        setupBridge()
        
        // Load the HTML file from assets
        webView.loadUrl("file:///android_asset/index.html")
    }

    /**
     * Configure WebView with secure and modern settings
     */
    private fun setupWebView() {
        val webSettings = webView.settings
        
        // Enable JavaScript
        webSettings.javaScriptEnabled = true
        
        // Enable DOM storage for local storage support
        webSettings.domStorageEnabled = true
        
        // Enable database storage
        webSettings.databaseEnabled = true
        
        // Allow file access
        webSettings.allowFileAccess = true
        
        // Enable zoom controls
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        
        // Modern viewport settings
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        
        // Cache settings for better performance
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        
        // Mixed content mode for HTTPS compatibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        // User agent string (optional customization)
        // webSettings.userAgentString = "${webSettings.userAgentString} WebViewApp/1.0"
        
        // WebViewClient for handling page navigation
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished loading: $url")
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "WebView error: $errorCode - $description at $failingUrl")
            }
        }
        
        // WebChromeClient for handling advanced features
        webView.webChromeClient = object : WebChromeClient() {
            // Handle file uploads
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback = filePathCallback
                
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = fileChooserParams?.acceptTypes?.firstOrNull()?.ifEmpty { "*/*" } ?: "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                
                filePickerLauncher.launch(intent)
                return true
            }
            
            // Handle console messages for debugging
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WebViewConsole", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
            
            // Handle geolocation permission
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
                super.onGeolocationPermissionsShowPrompt(origin, callback)
            }
        }
        
        // Handle back button navigation
        webView.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
                webView.goBack()
                true
            } else {
                false
            }
        }
    }

    /**
     * Set up the JavaScript bridge
     */
    private fun setupBridge() {
        bridge = WebViewBridge(this, webView, filePickerLauncher, cameraLauncher, permissionLauncher)
        
        // Add the JavaScript interface
        // Note: We expose the bridge interface, not arbitrary Java objects
        webView.addJavascriptInterface(bridge, "Android")
        
        // Also add a helper interface for cleaner JavaScript API
        webView.addJavascriptInterface(AndroidBridgeHelper(webView), "AndroidBridge")
    }

    /**
     * Handle file picker result
     */
    private fun handleFilePickerResult(resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) {
            val uris = if (data.clipData != null) {
                // Multiple files selected
                val uriList = mutableListOf<Uri>()
                for (i in 0 until data.clipData!!.itemCount) {
                    uriList.add(data.clipData!!.getItemAt(i).uri)
                }
                uriList.toTypedArray()
            } else {
                // Single file selected
                arrayOf(data.data)
            }.filterNotNull().toTypedArray()
            
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
            
            // Notify bridge about file selection
            notifyFileSelection(uris)
        } else {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
        }
    }

    /**
     * Handle camera result
     */
    private fun handleCameraResult(resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) {
            val extras = data.extras
            val bitmap = extras?.get("data") as? android.graphics.Bitmap
            
            if (bitmap != null) {
                // Convert bitmap to base64 and notify JavaScript
                val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                val base64Image = android.util.Base64.encodeToString(byteArrayOutputStream.toByteArray(), android.util.Base64.DEFAULT)
                
                val result = JSONObject().apply {
                    put("success", true)
                    put("imageData", base64Image)
                }
                
                runOnUiThread {
                    val jsCode = "javascript:if(typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.onCameraResult === 'function') {" +
                            "AndroidBridge.onCameraResult($result);" +
                            "}"
                    webView.evaluateJavascript(jsCode, null)
                }
            }
        }
    }

    /**
     * Handle permission launcher result
     */
    private fun handlePermissionLauncherResult(permissions: Map<String, Boolean>) {
        val permissionsArray = permissions.keys.toTypedArray()
        val grantResults = permissions.values.map { 
            if (it) PackageManager.PERMISSION_GRANTED 
            else PackageManager.PERMISSION_DENIED 
        }.toIntArray()
        
        bridge.handlePermissionResult(permissionsArray, grantResults)
    }

    /**
     * Notify JavaScript about file selection via bridge
     */
    private fun notifyFileSelection(uris: Array<Uri>) {
        try {
            val filesArray = org.json.JSONArray()
            
            uris.forEach { uri ->
                val fileObj = JSONObject().apply {
                    put("uri", uri.toString())
                    put("name", uri.lastPathSegment ?: "")
                    
                    // Try to get MIME type
                    try {
                        val mimeType = contentResolver.getType(uri)
                        put("type", mimeType ?: "")
                    } catch (e: Exception) {
                        put("type", "")
                    }
                    
                    // Try to get file size
                    try {
                        val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                        val size = parcelFileDescriptor?.statSize ?: 0
                        parcelFileDescriptor?.close()
                        put("size", size)
                    } catch (e: Exception) {
                        put("size", 0)
                    }
                }
                filesArray.put(fileObj)
            }
            
            val result = JSONObject().apply {
                put("success", true)
                put("files", filesArray)
            }
            
            runOnUiThread {
                val jsCode = "javascript:if(typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.onFilePickResult === 'function') {" +
                        "AndroidBridge.onFilePickResult($result);" +
                        "}"
                webView.evaluateJavascript(jsCode, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "notifyFileSelection error: ${e.message}", e)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        // Clean up WebView
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}

/**
 * Helper class for cleaner JavaScript callback interface
 */
class AndroidBridgeHelper(private val webView: WebView) {
    
    @android.webkit.JavascriptInterface
    fun onFilePickResult(jsonString: String) {
        postToWindow("onFilePickResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onFileSaveResult(jsonString: String) {
        postToWindow("onFileSaveResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onFileOpenResult(jsonString: String) {
        postToWindow("onFileOpenResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onDownloadResult(jsonString: String) {
        postToWindow("onDownloadResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onShareResult(jsonString: String) {
        postToWindow("onShareResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onNotificationResult(jsonString: String) {
        postToWindow("onNotificationResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onClipboardResult(jsonString: String) {
        postToWindow("onClipboardResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onVibrateResult(jsonString: String) {
        postToWindow("onVibrateResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onFullscreenResult(jsonString: String) {
        postToWindow("onFullscreenResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onKeepScreenOnResult(jsonString: String) {
        postToWindow("onKeepScreenOnResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onCameraResult(jsonString: String) {
        postToWindow("onCameraResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onLocationResult(jsonString: String) {
        postToWindow("onLocationResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onDeviceInfoResult(jsonString: String) {
        postToWindow("onDeviceInfoResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onBatteryResult(jsonString: String) {
        postToWindow("onBatteryResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onNetworkResult(jsonString: String) {
        postToWindow("onNetworkResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onScreenInfoResult(jsonString: String) {
        postToWindow("onScreenInfoResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onOpenUrlResult(jsonString: String) {
        postToWindow("onOpenUrlResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onOpenDialerResult(jsonString: String) {
        postToWindow("onOpenDialerResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onOpenEmailResult(jsonString: String) {
        postToWindow("onOpenEmailResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onOpenSettingsResult(jsonString: String) {
        postToWindow("onOpenSettingsResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onPermissionResult(jsonString: String) {
        postToWindow("onPermissionResult", jsonString)
    }
    
    @android.webkit.JavascriptInterface
    fun onPermissionCheckResult(jsonString: String) {
        postToWindow("onPermissionCheckResult", jsonString)
    }
    
    private fun postToWindow(callbackName: String, jsonString: String) {
        webView.post {
            val jsCode = "window.dispatchEvent(new CustomEvent('$callbackName', { detail: $jsonString }));"
            webView.evaluateJavascript(jsCode, null)
        }
    }
}
