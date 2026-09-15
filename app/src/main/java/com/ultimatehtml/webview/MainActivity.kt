package com.ultimatehtml.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(UNIVERSAL_SHIM, null)
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
                    Toast.makeText(
                        this@MainActivity,
                        "File picker failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
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

    companion object {
        // Universal shim — every HTML that loads will get this.
        // Makes any standard web file API route through the Android bridge.
        private val UNIVERSAL_SHIM = """
        (function(){
          if (window.__androidShimLoaded) return;
          window.__androidShimLoaded = true;

          try { delete window.showSaveFilePicker; } catch(e){}
          try { delete window.showOpenFilePicker; } catch(e){}
          try { delete window.showDirectoryPicker; } catch(e){}

          function _ab(blob){
            return new Promise(function(res, rej){
              var fr = new FileReader();
              fr.onload = function(){
                var s = fr.result; var i = s.indexOf(',');
                res(i >= 0 ? s.slice(i+1) : s);
              };
              fr.onerror = rej;
              fr.readAsDataURL(blob);
            });
          }
          window.__androidBlobToBase64 = _ab;

          if (window.Android && typeof window.Android.saveFile === 'function') {
            try {
              navigator.share = async function(data){
                try{
                  if (data && data.files && data.files.length) {
                    for (var i = 0; i < data.files.length; i++){
                      var f = data.files[i];
                      var b64 = await _ab(f);
                      window.Android.shareFile(f.name || 'file', f.type || 'application/octet-stream', b64);
                    }
                    return;
                  }
                  if (data && data.text) { window.Android.shareText(data.text); return; }
                  if (data && data.url)  { window.Android.shareText(data.url);  return; }
                }catch(e){ console.error('share shim', e); }
              };
              navigator.canShare = function(){ return true; };
            } catch(e){}
          }

          var _origClick = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function(){
            try{
              if (this.hasAttribute('download') &&
                  this.href && this.href.indexOf('blob:') === 0 &&
                  window.Android && typeof window.Android.saveFile === 'function') {
                var name = this.getAttribute('download') || 'file';
                fetch(this.href)
                  .then(function(r){ return r.blob(); })
                  .then(function(b){ return _ab(b); })
                  .then(function(b64){
                    window.Android.saveFile(name, b.type || 'application/octet-stream', b64);
                  })
                  .catch(function(e){ console.error('download shim', e); });
                return;
              }
            }catch(e){}
            return _origClick.apply(this, arguments);
          };
        })();
        """.trimIndent()
    }
}
