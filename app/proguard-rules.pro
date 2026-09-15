# Keep JS bridge methods
-keepclassmembers class com.ultimatehtml.webview.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}
