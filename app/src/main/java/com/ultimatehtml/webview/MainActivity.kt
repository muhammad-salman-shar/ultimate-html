package com.ultimatehtml.webview

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Main Activity - Hosts the WebView and initializes the JavaScript Bridge
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_FILE_PICK = 101
        private const val REQUEST_CAMERA_PHOTO = 102
        
        // Callbacks for async operations
        var filePickerCallback: ((List<Uri>) -> Unit)? = null
        var cameraCallback: String? = null
    }
    
    lateinit var webView: WebView
    private lateinit var jsBridge: JavaScriptBridge
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize JavaScript Bridge
        jsBridge = JavaScriptBridge(this)
        jsBridge.initializeNotificationChannels()
        
        // Setup WebView
        setupWebView()
        
        // Load index.html from assets
        webView.loadUrl("file:///android_asset/index.html")
        
        // Request necessary permissions
        requestNecessaryPermissions()
    }
    
    /**
     * Configure WebView with proper settings
     */
    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.apply {
                // Enable JavaScript
                javaScriptEnabled = true
                
                // Enable DOM storage
                domStorageEnabled = true
                
                // Enable local storage
                databaseEnabled = true
                
                // Allow file access
                allowFileAccess = true
                allowContentAccess = true
                
                // Enable zoom controls
                builtInZoomControls = true
                displayZoomControls = false
                
                // Modern viewport handling
                useWideViewPort = true
                loadWithOverviewMode = true
                
                // Enable media playback
                mediaPlaybackRequiresUserGesture = false
                
                // Mixed content mode for HTTPS compatibility
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                
                // Cache settings
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            
            // Set WebViewClient for page navigation
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page finished loading: $url")
                    
                    // Inject callback handler after page loads
                    injectCallbackHandler()
                }
                
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.e(TAG, "WebView error: $description at $failingUrl")
                }
            }
            
            // Set WebChromeClient for advanced features
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    Log.d(TAG, "Loading progress: $newProgress%")
                }
                
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage != null) {
                        Log.d("WebViewConsole", "${consoleMessage.message()} @ ${consoleMessage.lineNumber()}")
                    }
                    return true
                }
            }
            
            // Add JavaScript interface
            addJavascriptInterface(jsBridge, "Android")
        }
        
        setContentView(webView)
    }
    
    /**
     * Inject JavaScript callback handler into the page
     */
    private fun injectCallbackHandler() {
        val js = """
            if (!window.__androidCallbacks) {
                window.__androidCallbacks = {};
                window.__callbackCounter = 0;
                
                window.__createCallback = function(callback) {
                    const callbackId = 'cb_' + (++window.__callbackCounter);
                    window.__androidCallbacks[callbackId] = function(result) {
                        callback(result);
                        delete window.__androidCallbacks[callbackId];
                    };
                    return callbackId;
                };
            }
        """.trimIndent()
        
        webView.evaluateJavascript(js, null)
    }
    
    /**
     * Request necessary runtime permissions
     */
    private fun requestNecessaryPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        
        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Camera permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        
        // Location permissions
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }
    
    /**
     * Handle activity results for file picker and camera
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            Companion.REQUEST_PERMISSIONS -> {
                Log.d(TAG, "Permission request result: $resultCode")
            }
            
            Companion.REQUEST_FILE_PICK -> {
                handleFilePickerResult(resultCode, data)
            }
            
            Companion.REQUEST_CAMERA_PHOTO -> {
                handleCameraResult(resultCode, data)
            }
        }
    }
    
    /**
     * Handle file picker result
     */
    private fun handleFilePickerResult(resultCode: Int, data: android.content.Intent?) {
        val uriList = mutableListOf<Uri>()
        
        if (resultCode == RESULT_OK && data != null) {
            data.data?.let { uriList.add(it) }
            data.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uriList.add(it) }
                }
            }
        }
        
        filePickerCallback?.invoke(uriList)
        filePickerCallback = null
    }
    
    /**
     * Handle camera result
     */
    private fun handleCameraResult(resultCode: Int, data: android.content.Intent?) {
        val callbackId = cameraCallback ?: return
        
        if (resultCode == RESULT_OK && data != null) {
            val imageUri = data.extras?.get("data") as? android.graphics.Bitmap
            if (imageUri != null) {
                // Convert bitmap to base64 and return to JS
                val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                imageUri.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                val base64Image = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)
                
                val js = """
                    if (window.__androidCallbacks['$callbackId']) {
                        window.__androidCallbacks['$callbackId']({
                            "success": true,
                            "imageBase64": "$base64Image"
                        });
                    }
                """.trimIndent()
                
                webView.evaluateJavascript(js, null)
            }
        } else {
            val js = """
                if (window.__androidCallbacks['$callbackId']) {
                    window.__androidCallbacks['$callbackId']({"error": "cancelled"});
                }
            """.trimIndent()
            
            webView.evaluateJavascript(js, null)
        }
        
        cameraCallback = null
    }
    
    /**
     * Handle back button press
     */
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
