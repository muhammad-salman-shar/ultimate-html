package com.ultimatehtml.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                when {
                    data == null -> null
                    data.clipData != null -> {
                        val clip = data.clipData!!
                        Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            cb.onReceiveValue(uris)
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.addJavascriptInterface(AndroidBridge(this, webView), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleExternalUrl(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent: Intent = try {
                    params?.createIntent() ?: fallbackIntent()
                } catch (e: Exception) {
                    fallbackIntent()
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, "File picker failed", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun handleExternalUrl(url: String): Boolean {
        val lower = url.lowercase()

        val external = when {
            lower.startsWith("whatsapp://") -> true
            lower.contains("wa.me/") -> true
            lower.contains("api.whatsapp.com") -> true
            lower.contains("chat.whatsapp.com") -> true
            lower.startsWith("tel:") -> true
            lower.startsWith("mailto:") -> true
            lower.startsWith("sms:") -> true
            lower.startsWith("intent:") -> true
            lower.startsWith("market://") -> true
            lower.startsWith("upi://") -> true
            !lower.startsWith("file://") && !lower.startsWith("data:") &&
                (lower.startsWith("http://") || lower.startsWith("https://")) -> true
            else -> false
        }

        if (!external) return false

        return try {
            val intent = if (lower.startsWith("http")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun fallbackIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
