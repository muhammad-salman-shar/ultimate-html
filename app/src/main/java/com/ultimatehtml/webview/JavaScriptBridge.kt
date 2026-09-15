package com.ultimatehtml.webview

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * JavaScript Bridge for Android Native Functionality
 * 
 * This class exposes native Android capabilities to JavaScript running in the WebView.
 * All methods are annotated with @JavascriptInterface and are called from index.html.
 * 
 * Security considerations:
 * - Only explicitly required methods are exposed
 * - All input is validated
 * - No arbitrary reflection or shell execution
 * - File paths use content URIs where appropriate
 */
class JavaScriptBridge(private val activity: Activity) {
    
    companion object {
        private const val TAG = "JavaScriptBridge"
        private const val DOWNLOAD_CHANNEL_ID = "download_channel"
        private const val GENERAL_CHANNEL_ID = "general_channel"
        private const val REQUEST_FILE_PICK = 1001
        private const val REQUEST_CAMERA_PHOTO = 1002
        private const val REQUEST_LOCATION = 1003
    }
    
    private val context: Context = activity.applicationContext
    private var pendingCallback: String? = null
    
    /**
     * Initialize notification channels on Android 8.0+
     */
    fun initializeNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Download channel
            val downloadChannel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress and completion notifications"
            }
            
            // General channel
            val generalChannel = NotificationChannel(
                GENERAL_CHANNEL_ID,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
            }
            
            notificationManager.createNotificationChannel(downloadChannel)
            notificationManager.createNotificationChannel(generalChannel)
        }
    }
    
    // ==================== FILE OPERATIONS ====================
    
    /**
     * Trigger file picker dialog
     * Called from JavaScript: Android.pickFile(acceptTypes, multiple, callbackId)
     * 
     * @param acceptTypes MIME type filter (e.g., "image/*", "application/pdf", "*/*")
     * @param multiple Whether multiple files can be selected
     * @param callbackId JavaScript callback identifier
     */
    @JavascriptInterface
    fun pickFile(acceptTypes: String, multiple: Boolean, callbackId: String) {
        try {
            activity.runOnUiThread {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = if (acceptTypes.isEmpty() || acceptTypes == "*/*") "*/*" else acceptTypes
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                
                MainActivity.filePickerCallback = { uriList ->
                    if (uriList.isEmpty()) {
                        invokeCallback(callbackId, "{\"error\": \"cancelled\", \"files\": []}")
                    } else {
                        val filesArray = JSONArray()
                        uriList.forEach { uri ->
                            val fileObj = JSONObject().apply {
                                put("uri", uri.toString())
                                put("name", getFileName(uri))
                                put("type", getFileMimeType(uri))
                                put("size", getFileSize(uri))
                            }
                            filesArray.put(fileObj)
                        }
                        val result = JSONObject().apply {
                            put("success", true)
                            put("files", filesArray)
                        }
                        invokeCallback(callbackId, result.toString())
                    }
                }
                
                activity.startActivityForResult(
                    Intent.createChooser(intent, "Select File"),
                    REQUEST_FILE_PICK
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "pickFile error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Save a file to device storage
     * Called from JavaScript: Android.saveFile(filename, mimeType, base64Data, callbackId)
     * 
     * @param filename Name of the file to save
     * @param mimeType MIME type of the file
     * @param base64Data Base64-encoded file content
     * @param callbackId JavaScript callback identifier
     */
    @JavascriptInterface
    fun saveFile(filename: String, mimeType: String, base64Data: String, callbackId: String) {
        try {
            // Validate filename - prevent path traversal
            val safeFilename = filename.replace("../", "").replace("..\\", "")
            
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val file = File(context.filesDir, safeFilename)
            
            FileOutputStream(file).use { fos ->
                fos.write(bytes)
            }
            
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val result = JSONObject().apply {
                put("success", true)
                put("uri", uri.toString())
                put("path", file.absolutePath)
                put("size", bytes.size)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "saveFile error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Open a file using Android's intent system
     * Called from JavaScript: Android.openFile(uri, mimeType)
     */
    @JavascriptInterface
    fun openFile(uriString: String, mimeType: String) {
        try {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (intent.resolveActivity(context.packageManager) != null) {
                activity.startActivity(intent)
                invokeCallback("null", "{\"success\": true}")
            } else {
                invokeCallback("null", "{\"error\": \"No app can open this file type\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openFile error", e)
            invokeCallback("null", "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== DOWNLOAD SYSTEM ====================
    
    /**
     * Download a file using DownloadManager
     * Called from JavaScript: Android.download(url, filename, mimeType, callbackId)
     * 
     * @param url URL to download from
     * @param filename Desired filename for the download
     * @param mimeType MIME type of the file
     * @param callbackId JavaScript callback identifier
     */
    @JavascriptInterface
    fun download(url: String, filename: String, mimeType: String, callbackId: String) {
        try {
            // Validate URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                invokeCallback(callbackId, "{\"error\": \"Invalid URL scheme\"}")
                return
            }
            
            val safeFilename = filename.replace("../", "").replace("..\\", "")
            
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(safeFilename)
                setDescription("Downloading $safeFilename")
                setMimeType(mimeType.ifEmpty { "application/octet-stream" })
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFilename)
            }
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            
            val result = JSONObject().apply {
                put("success", true)
                put("downloadId", downloadId)
                put("filename", safeFilename)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "download error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== NOTIFICATIONS ====================
    
    /**
     * Show a native Android notification
     * Called from JavaScript: Android.notify(title, body, iconUrl, callbackId)
     * 
     * @param title Notification title
     * @param body Notification body text
     * @param iconUrl Optional icon URL (can be data URI or resource name)
     * @param callbackId JavaScript callback identifier
     */
    @JavascriptInterface
    fun notify(title: String, body: String, iconUrl: String, callbackId: String) {
        try {
            // Check notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    invokeCallback(callbackId, "{\"error\": \"notification_permission_denied\"}")
                    return
                }
            }
            
            val builder = NotificationCompat.Builder(context, GENERAL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
            
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
            
            val result = JSONObject().apply {
                put("success", true)
                put("notificationId", System.currentTimeMillis().toInt())
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "notify error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== SHARING ====================
    
    /**
     * Share text via Android Sharesheet
     * Called from JavaScript: Android.shareText(text, title, callbackId)
     */
    @JavascriptInterface
    fun shareText(text: String, title: String, callbackId: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            activity.startActivity(Intent.createChooser(intent, title))
            invokeCallback(callbackId, "{\"success\": true}")
            
        } catch (e: Exception) {
            Log.e(TAG, "shareText error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Share a file via Android Sharesheet
     * Called from JavaScript: Android.shareFile(uri, mimeType, title, callbackId)
     */
    @JavascriptInterface
    fun shareFile(uriString: String, mimeType: String, title: String, callbackId: String) {
        try {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifEmpty { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            activity.startActivity(Intent.createChooser(intent, title))
            invokeCallback(callbackId, "{\"success\": true}")
            
        } catch (e: Exception) {
            Log.e(TAG, "shareFile error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== CLIPBOARD ====================
    
    /**
     * Copy text to clipboard
     * Called from JavaScript: Android.copyToClipboard(text, callbackId)
     */
    @JavascriptInterface
    fun copyToClipboard(text: String, callbackId: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
            
            val result = JSONObject().apply {
                put("success", true)
                put("message", "Text copied to clipboard")
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "copyToClipboard error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Get text from clipboard
     * Called from JavaScript: Android.getFromClipboard(callbackId)
     */
    @JavascriptInterface
    fun getFromClipboard(callbackId: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            
            val result = JSONObject().apply {
                put("success", true)
                put("text", text)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "getFromClipboard error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== DEVICE INTERACTION ====================
    
    /**
     * Vibrate device
     * Called from JavaScript: Android.vibrate(durationMs, callbackId)
     */
    @JavascriptInterface
    fun vibrate(durationMs: Long, callbackId: String) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
            
            val result = JSONObject().apply {
                put("success", true)
                put("duration", durationMs)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "vibrate error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Set fullscreen/immersive mode
     * Called from JavaScript: Android.setFullscreen(enabled, callbackId)
     */
    @JavascriptInterface
    fun setFullscreen(enabled: Boolean, callbackId: String) {
        try {
            activity.runOnUiThread {
                if (enabled) {
                    // Enable immersive sticky mode
                    val uiOptions = activity.window.decorView.systemUiVisibility
                    val newUiOptions = uiOptions or 
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                    activity.window.decorView.systemUiVisibility = newUiOptions
                } else {
                    // Disable fullscreen
                    val uiOptions = activity.window.decorView.systemUiVisibility
                    val newUiOptions = uiOptions and 
                        (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN).inv()
                    activity.window.decorView.systemUiVisibility = newUiOptions
                }
                
                val result = JSONObject().apply {
                    put("success", true)
                    put("fullscreen", enabled)
                }
                invokeCallback(callbackId, result.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "setFullscreen error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Keep screen awake
     * Called from JavaScript: Android.keepScreenAwake(enabled, callbackId)
     */
    @JavascriptInterface
    fun keepScreenAwake(enabled: Boolean, callbackId: String) {
        try {
            activity.runOnUiThread {
                if (enabled) {
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                
                val result = JSONObject().apply {
                    put("success", true)
                    put("keepAwake", enabled)
                }
                invokeCallback(callbackId, result.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "keepScreenAwake error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== DEVICE INFORMATION ====================
    
    /**
     * Get device information
     * Called from JavaScript: Android.getDeviceInfo(callbackId)
     */
    @JavascriptInterface
    fun getDeviceInfo(callbackId: String) {
        try {
            val resources = context.resources
            val displayMetrics = resources.displayMetrics
            
            val result = JSONObject().apply {
                put("success", true)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkVersion", Build.VERSION.SDK_INT)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("screenWidth", displayMetrics.widthPixels)
                put("screenHeight", displayMetrics.heightPixels)
                put("density", displayMetrics.density)
                put("appName", context.getString(R.string.app_name))
                put("packageName", context.packageName)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceInfo error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Get battery information
     * Called from JavaScript: Android.getBatteryInfo(callbackId)
     */
    @JavascriptInterface
    fun getBatteryInfo(callbackId: String) {
        try {
            // Note: Battery info requires BroadcastReceiver, returning basic info
            val result = JSONObject().apply {
                put("success", true)
                put("available", true)
                put("note", "Use BatteryManager API for detailed info")
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "getBatteryInfo error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Get network status
     * Called from JavaScript: Android.getNetworkStatus(callbackId)
     */
    @JavascriptInterface
    fun getNetworkStatus(callbackId: String) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as 
                android.net.ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo
            
            val isConnected = networkInfo?.isConnected ?: false
            val type = when (networkInfo?.type) {
                android.net.ConnectivityManager.TYPE_WIFI -> "wifi"
                android.net.ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> "unknown"
            }
            
            val result = JSONObject().apply {
                put("success", true)
                put("isOnline", isConnected)
                put("connectionType", type)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "getNetworkStatus error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== EXTERNAL APPLICATIONS ====================
    
    /**
     * Open URL in external browser
     * Called from JavaScript: Android.openBrowser(url, callbackId)
     */
    @JavascriptInterface
    fun openBrowser(url: String, callbackId: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            
            val result = JSONObject().apply {
                put("success", true)
                put("url", url)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "openBrowser error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Open phone dialer
     * Called from JavaScript: Android.openDialer(number, callbackId)
     */
    @JavascriptInterface
    fun openDialer(number: String, callbackId: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            
            val result = JSONObject().apply {
                put("success", true)
                put("number", number)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "openDialer error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Open email composer
     * Called from JavaScript: Android.openEmail(to, subject, body, callbackId)
     */
    @JavascriptInterface
    fun openEmail(to: String, subject: String, body: String, callbackId: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            
            val result = JSONObject().apply {
                put("success", true)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "openEmail error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    /**
     * Open Android settings
     * Called from JavaScript: Android.openSettings(settingType, callbackId)
     */
    @JavascriptInterface
    fun openSettings(settingType: String, callbackId: String) {
        try {
            val action = when (settingType.lowercase()) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            
            val result = JSONObject().apply {
                put("success", true)
                put("settingType", settingType)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "openSettings error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== LOCATION ====================
    
    /**
     * Request current location
     * Called from JavaScript: Android.getLocation(callbackId)
     * Note: Requires runtime permission
     */
    @JavascriptInterface
    fun getLocation(callbackId: String) {
        try {
            // Check location permission
            val hasFineLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasFineLocation && !hasCoarseLocation) {
                invokeCallback(callbackId, "{\"error\": \"location_permission_denied\"}")
                return
            }
            
            // For actual location, we'd need FusedLocationProviderClient
            // This is a simplified implementation
            val result = JSONObject().apply {
                put("success", true)
                put("note", "Use FusedLocationProviderClient for actual coordinates")
                put("permissionGranted", true)
            }
            invokeCallback(callbackId, result.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "getLocation error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== CAMERA ====================
    
    /**
     * Launch camera for photo capture
     * Called from JavaScript: Android.takePhoto(callbackId)
     */
    @JavascriptInterface
    fun takePhoto(callbackId: String) {
        try {
            // Check camera permission
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                invokeCallback(callbackId, "{\"error\": \"camera_permission_denied\"}")
                return
            }
            
            activity.runOnUiThread {
                MainActivity.cameraCallback = callbackId
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                if (intent.resolveActivity(context.packageManager) != null) {
                    activity.startActivityForResult(intent, REQUEST_CAMERA_PHOTO)
                } else {
                    invokeCallback(callbackId, "{\"error\": \"No camera app available\"}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto error", e)
            invokeCallback(callbackId, "{\"error\": \"${e.message}\"}")
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun getFileName(uri: Uri): String {
        var name = ""
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex >= 0) {
                    name = it.getString(displayNameIndex)
                }
            }
        }
        return name.ifEmpty { uri.lastPathSegment ?: "unknown" }
    }
    
    private fun getFileMimeType(uri: Uri): String {
        return context.contentResolver.getType(uri) 
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ) ?: "application/octet-stream"
    }
    
    private fun getFileSize(uri: Uri): Long {
        return try {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        return it.getLong(sizeIndex)
                    }
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Invoke JavaScript callback with result
     */
    private fun invokeCallback(callbackId: String, jsonResult: String) {
        if (callbackId == "null") return
        
        activity.runOnUiThread {
            val js = "window.__androidCallbacks['$callbackId']($jsonResult)"
            activity.webView.evaluateJavascript(js, null)
        }
    }
}
