package com.webviewapp

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JavaScript Bridge Interface
 * 
 * This interface defines all methods that can be called from JavaScript.
 * All methods are annotated with @JavascriptInterface to expose them to the WebView.
 * 
 * Security: Only explicitly defined methods are exposed. No reflection or arbitrary code execution.
 */
interface JavaScriptBridge {
    
    // ========== FILE OPERATIONS ==========
    
    /**
     * Pick a file using Android's file picker
     * @param mimeType Filter for file types (e.g., "image/*", "*/*", "application/pdf")
     * @param allowMultiple "true" or "false" for multiple selection
     * Callback: AndroidBridge.onFilePickResult(jsonResult)
     */
    @JavascriptInterface
    fun pickFile(mimeType: String, allowMultiple: String)
    
    /**
     * Save a file to device storage
     * @param filename Name of the file to save
     * @param content Base64 encoded content
     * @param mimeType MIME type of the file
     * Callback: AndroidBridge.onFileSaveResult(jsonResult)
     */
    @JavascriptInterface
    fun saveFile(filename: String, content: String, mimeType: String)
    
    /**
     * Open a file using Android's intent system
     * @param filename Name of the file to open
     * @param mimeType MIME type of the file
     * Callback: AndroidBridge.onFileOpenResult(jsonResult)
     */
    @JavascriptInterface
    fun openFile(filename: String, mimeType: String)
    
    // ========== DOWNLOADS ==========
    
    /**
     * Download a file using DownloadManager
     * @param url URL to download from
     * @param filename Name to save as
     * @param mimeType MIME type (optional, can be empty)
     * Callback: AndroidBridge.onDownloadResult(jsonResult)
     */
    @JavascriptInterface
    fun download(url: String, filename: String, mimeType: String)
    
    // ========== SHARING ==========
    
    /**
     * Share text via Android Sharesheet
     * @param text Text content to share
     * @param title Optional title for the share intent
     * Callback: AndroidBridge.onShareResult(jsonResult)
     */
    @JavascriptInterface
    fun shareText(text: String, title: String)
    
    /**
     * Share a file via Android Sharesheet
     * @param filename Name of the file to share
     * @param mimeType MIME type of the file
     * Callback: AndroidBridge.onShareResult(jsonResult)
     */
    @JavascriptInterface
    fun shareFile(filename: String, mimeType: String)
    
    // ========== NOTIFICATIONS ==========
    
    /**
     * Show a native Android notification
     * @param title Notification title
     * @param body Notification body text
     * @param channelId Optional channel ID (uses default if empty)
     * Callback: AndroidBridge.onNotificationResult(jsonResult)
     */
    @JavascriptInterface
    fun notify(title: String, body: String, channelId: String)
    
    /**
     * Cancel a notification by ID
     * @param notificationId ID of notification to cancel
     */
    @JavascriptInterface
    fun cancelNotification(notificationId: Int)
    
    // ========== CLIPBOARD ==========
    
    /**
     * Copy text to clipboard
     * @param text Text to copy
     * Callback: AndroidBridge.onClipboardResult(jsonResult)
     */
    @JavascriptInterface
    fun copyToClipboard(text: String)
    
    /**
     * Read text from clipboard
     * Callback: AndroidBridge.onClipboardResult(jsonResult)
     */
    @JavascriptInterface
    fun readFromClipboard()
    
    // ========== DEVICE INTERACTION ==========
    
    /**
     * Vibrate the device
     * @param durationMs Duration in milliseconds
     */
    @JavascriptInterface
    fun vibrate(durationMs: Long)
    
    /**
     * Toggle fullscreen/immersive mode
     * @param enable "true" to enable fullscreen, "false" to disable
     */
    @JavascriptInterface
    fun setFullscreen(enable: String)
    
    /**
     * Keep screen on/off
     * @param enable "true" to keep screen on, "false" to allow sleep
     */
    @JavascriptInterface
    fun keepScreenOn(enable: String)
    
    // ========== CAMERA / MEDIA ==========
    
    /**
     * Launch camera to take a photo
     * Callback: AndroidBridge.onCameraResult(jsonResult)
     */
    @JavascriptInterface
    fun takePhoto()
    
    /**
     * Launch video recording
     * Callback: AndroidBridge.onCameraResult(jsonResult)
     */
    @JavascriptInterface
    fun recordVideo()
    
    // ========== LOCATION ==========
    
    /**
     * Get current location
     * Callback: AndroidBridge.onLocationResult(jsonResult)
     */
    @JavascriptInterface
    fun getLocation()
    
    // ========== DEVICE INFO ==========
    
    /**
     * Get device information
     * Callback: AndroidBridge.onDeviceInfoResult(jsonResult)
     */
    @JavascriptInterface
    fun getDeviceInfo()
    
    /**
     * Get battery information
     * Callback: AndroidBridge.onBatteryResult(jsonResult)
     */
    @JavascriptInterface
    fun getBatteryInfo()
    
    /**
     * Get network information
     * Callback: AndroidBridge.onNetworkResult(jsonResult)
     */
    @JavascriptInterface
    fun getNetworkInfo()
    
    /**
     * Get screen dimensions
     * Callback: AndroidBridge.onScreenInfoResult(jsonResult)
     */
    @JavascriptInterface
    fun getScreenInfo()
    
    // ========== EXTERNAL APPS ==========
    
    /**
     * Open a URL in external browser
     * @param url URL to open
     * Callback: AndroidBridge.onOpenUrlResult(jsonResult)
     */
    @JavascriptInterface
    fun openUrl(url: String)
    
    /**
     * Open phone dialer with number
     * @param phoneNumber Phone number to dial
     * Callback: AndroidBridge.onOpenDialerResult(jsonResult)
     */
    @JavascriptInterface
    fun openDialer(phoneNumber: String)
    
    /**
     * Open email composer
     * @param email Email address
     * @param subject Optional subject
     * @param body Optional body text
     * Callback: AndroidBridge.onOpenEmailResult(jsonResult)
     */
    @JavascriptInterface
    fun openEmail(email: String, subject: String, body: String)
    
    /**
     * Open Android settings
     * @param settingsAction Settings action (e.g., "SETTINGS", "WIFI_SETTINGS")
     * Callback: AndroidBridge.onOpenSettingsResult(jsonResult)
     */
    @JavascriptInterface
    fun openSettings(settingsAction: String)
    
    // ========== PERMISSIONS ==========
    
    /**
     * Request runtime permissions
     * @param permissions JSON array of permission strings
     * Callback: AndroidBridge.onPermissionResult(jsonResult)
     */
    @JavascriptInterface
    fun requestPermissions(permissions: String)
    
    /**
     * Check if a permission is granted
     * @param permission Permission string to check
     * Callback: AndroidBridge.onPermissionCheckResult(jsonResult)
     */
    @JavascriptInterface
    fun checkPermission(permission: String)
}
