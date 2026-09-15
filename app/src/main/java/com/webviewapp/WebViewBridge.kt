package com.webviewapp

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Implementation of the JavaScript Bridge
 * 
 * This class implements all bridge methods and handles communication
 * between JavaScript and native Android functionality.
 */
class WebViewBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val filePickerLauncher: ActivityResultLauncher<Intent>,
    private val cameraLauncher: ActivityResultLauncher<Intent>,
    private val permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) : JavaScriptBridge {

    companion object {
        private const val TAG = "WebViewBridge"
        private const val NOTIFICATION_CHANNEL_ID = "webview_app_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "App Notifications"
        private var notificationCounter = 1000
    }

    // ========== UTILITY METHODS ==========

    /**
     * Post a result back to JavaScript on the UI thread
     */
    private fun postResult(callbackName: String, jsonResult: JSONObject) {
        activity.runOnUiThread {
            val jsonString = jsonResult.toString()
            val jsCode = "javascript:if(typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.$callbackName === 'function') {" +
                    "AndroidBridge.$callbackName($jsonString);" +
                    "}"
            webView.post {
                webView.evaluateJavascript(jsCode, null)
            }
        }
    }

    /**
     * Create a success response JSON
     */
    private fun successResponse(data: Map<String, Any?> = emptyMap()): JSONObject {
        return JSONObject().apply {
            put("success", true)
            put("error", JSONObject.NULL)
            data.forEach { (key, value) ->
                put(key, value ?: JSONObject.NULL)
            }
        }
    }

    /**
     * Create an error response JSON
     */
    private fun errorResponse(errorType: String, message: String): JSONObject {
        return JSONObject().apply {
            put("success", false)
            put("error", JSONObject().apply {
                put("type", errorType)
                put("message", message)
            })
        }
    }

    /**
     * Check if a permission is granted
     */
    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request permissions with callback
     */
    private fun requestPermissionsInternal(permissions: List<String>) {
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // ========== FILE OPERATIONS ==========

    override fun pickFile(mimeType: String, allowMultiple: String) {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = mimeType.ifEmpty { "*/*" }
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple == "true")
            }
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "pickFile error: ${e.message}", e)
            postResult("onFilePickResult", errorResponse("file_picker_error", e.message ?: "Unknown error"))
        }
    }

    override fun saveFile(filename: String, content: String, mimeType: String) {
        try {
            // Validate filename - prevent path traversal
            val safeFilename = filename.replace("../", "").replace("..\\", "")
            if (safeFilename.isEmpty() || safeFilename.contains("/") || safeFilename.contains("\\")) {
                postResult("onFileSaveResult", errorResponse("invalid_filename", "Invalid filename"))
                return
            }

            // Decode base64 content
            val decodedContent = Base64.decode(content, Base64.DEFAULT)

            // Save to app's files directory
            val file = File(activity.filesDir, safeFilename)
            FileOutputStream(file).use { fos ->
                fos.write(decodedContent)
            }

            postResult("onFileSaveResult", successResponse(mapOf(
                "filename" to safeFilename,
                "path" to file.absolutePath,
                "size" to file.length()
            )))
        } catch (e: Exception) {
            Log.e(TAG, "saveFile error: ${e.message}", e)
            postResult("onFileSaveResult", errorResponse("save_error", e.message ?: "Failed to save file"))
        }
    }

    override fun openFile(filename: String, mimeType: String) {
        try {
            val file = File(activity.filesDir, filename)
            if (!file.exists()) {
                postResult("onFileOpenResult", errorResponse("file_not_found", "File not found: $filename"))
                return
            }

            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(intent)
            postResult("onFileOpenResult", successResponse(mapOf("filename" to filename)))
        } catch (e: Exception) {
            Log.e(TAG, "openFile error: ${e.message}", e)
            postResult("onFileOpenResult", errorResponse("open_error", e.message ?: "Failed to open file"))
        }
    }

    // ========== DOWNLOADS ==========

    override fun download(url: String, filename: String, mimeType: String) {
        try {
            if (url.isBlank()) {
                postResult("onDownloadResult", errorResponse("invalid_url", "URL is required"))
                return
            }

            val safeFilename = filename.ifEmpty { URL(url).host }
                .replace("../", "")
                .replace("..\\", "")

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(safeFilename)
                setDescription("Downloading...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setMimeType(mimeType.ifEmpty { "*/*" })
                
                // Save to Downloads folder
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFilename)
            }

            val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            postResult("onDownloadResult", successResponse(mapOf(
                "downloadId" to downloadId,
                "filename" to safeFilename,
                "url" to url
            )))
        } catch (e: Exception) {
            Log.e(TAG, "download error: ${e.message}", e)
            postResult("onDownloadResult", errorResponse("download_error", e.message ?: "Failed to start download"))
        }
    }

    // ========== SHARING ==========

    override fun shareText(text: String, title: String) {
        try {
            if (text.isBlank()) {
                postResult("onShareResult", errorResponse("invalid_input", "Text is required"))
                return
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            activity.startActivity(Intent.createChooser(intent, title.ifEmpty { "Share" }))
            postResult("onShareResult", successResponse())
        } catch (e: Exception) {
            Log.e(TAG, "shareText error: ${e.message}", e)
            postResult("onShareResult", errorResponse("share_error", e.message ?: "Failed to share"))
        }
    }

    override fun shareFile(filename: String, mimeType: String) {
        try {
            val file = File(activity.filesDir, filename)
            if (!file.exists()) {
                postResult("onShareResult", errorResponse("file_not_found", "File not found: $filename"))
                return
            }

            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifEmpty { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            activity.startActivity(Intent.createChooser(intent, "Share"))
            postResult("onShareResult", successResponse(mapOf("filename" to filename)))
        } catch (e: Exception) {
            Log.e(TAG, "shareFile error: ${e.message}", e)
            postResult("onShareResult", errorResponse("share_error", e.message ?: "Failed to share file"))
        }
    }

    // ========== NOTIFICATIONS ==========

    override fun notify(title: String, body: String, channelId: String) {
        try {
            if (title.isBlank() && body.isBlank()) {
                postResult("onNotificationResult", errorResponse("invalid_input", "Title or body is required"))
                return
            }

            val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create notification channel for Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId.ifEmpty { NOTIFICATION_CHANNEL_ID },
                    channelId.ifEmpty { NOTIFICATION_CHANNEL_NAME },
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "App notifications"
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notificationId = notificationCounter++

            val notification = NotificationCompat.Builder(activity, channelId.ifEmpty { NOTIFICATION_CHANNEL_ID })
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationId, notification)

            postResult("onNotificationResult", successResponse(mapOf("notificationId" to notificationId)))
        } catch (e: Exception) {
            Log.e(TAG, "notify error: ${e.message}", e)
            postResult("onNotificationResult", errorResponse("notification_error", e.message ?: "Failed to show notification"))
        }
    }

    override fun cancelNotification(notificationId: Int) {
        try {
            val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
            postResult("onNotificationResult", successResponse(mapOf("notificationId" to notificationId)))
        } catch (e: Exception) {
            Log.e(TAG, "cancelNotification error: ${e.message}", e)
        }
    }

    // ========== CLIPBOARD ==========

    override fun copyToClipboard(text: String) {
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("label", text)
            clipboard.setPrimaryClip(clip)
            
            Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            postResult("onClipboardResult", successResponse())
        } catch (e: Exception) {
            Log.e(TAG, "copyToClipboard error: ${e.message}", e)
            postResult("onClipboardResult", errorResponse("clipboard_error", e.message ?: "Failed to copy"))
        }
    }

    override fun readFromClipboard() {
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(activity).toString()
                postResult("onClipboardResult", successResponse(mapOf("text" to text)))
            } else {
                postResult("onClipboardResult", errorResponse("empty_clipboard", "Clipboard is empty"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "readFromClipboard error: ${e.message}", e)
            postResult("onClipboardResult", errorResponse("clipboard_error", e.message ?: "Failed to read clipboard"))
        }
    }

    // ========== DEVICE INTERACTION ==========

    override fun vibrate(durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }

            postResult("onVibrateResult", successResponse())
        } catch (e: Exception) {
            Log.e(TAG, "vibrate error: ${e.message}", e)
        }
    }

    override fun setFullscreen(enable: String) {
        activity.runOnUiThread {
            try {
                val decorView = activity.window.decorView
                if (enable == "true") {
                    // Enable immersive fullscreen
                    decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
                } else {
                    // Disable fullscreen
                    decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
                postResult("onFullscreenResult", successResponse(mapOf("fullscreen" to (enable == "true"))))
            } catch (e: Exception) {
                Log.e(TAG, "setFullscreen error: ${e.message}", e)
            }
        }
    }

    override fun keepScreenOn(enable: String) {
        activity.runOnUiThread {
            try {
                if (enable == "true") {
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                postResult("onKeepScreenOnResult", successResponse(mapOf("keepScreenOn" to (enable == "true"))))
            } catch (e: Exception) {
                Log.e(TAG, "keepScreenOn error: ${e.message}", e)
            }
        }
    }

    // ========== CAMERA / MEDIA ==========

    override fun takePhoto() {
        try {
            val intent = Intent("android.media.action.IMAGE_CAPTURE")
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto error: ${e.message}", e)
            postResult("onCameraResult", errorResponse("camera_error", e.message ?: "Failed to launch camera"))
        }
    }

    override fun recordVideo() {
        try {
            val intent = Intent("android.media.action.VIDEO_CAPTURE")
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "recordVideo error: ${e.message}", e)
            postResult("onCameraResult", errorResponse("camera_error", e.message ?: "Failed to launch camera"))
        }
    }

    // ========== LOCATION ==========

    override fun getLocation() {
        try {
            // Check for location permissions
            val fineLocationGranted = isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseLocationGranted = isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (!fineLocationGranted && !coarseLocationGranted) {
                requestPermissionsInternal(listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
                // Result will be posted via permission callback
                return
            }

            // Use fused location provider or GPS directly
            val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            
            try {
                val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

                if (location != null) {
                    postResult("onLocationResult", successResponse(mapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "accuracy" to location.accuracy,
                        "altitude" to location.altitude,
                        "time" to location.time
                    )))
                } else {
                    postResult("onLocationResult", errorResponse("location_unavailable", "Location unavailable"))
                }
            } catch (e: SecurityException) {
                postResult("onLocationResult", errorResponse("permission_denied", "Location permission denied"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocation error: ${e.message}", e)
            postResult("onLocationResult", errorResponse("location_error", e.message ?: "Failed to get location"))
        }
    }

    // ========== DEVICE INFO ==========

    override fun getDeviceInfo() {
        try {
            val deviceInfo = JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("device", Build.DEVICE)
                put("product", Build.PRODUCT)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkVersion", Build.VERSION.SDK_INT)
                put("appVersion", try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                } catch (e: Exception) { "unknown" })
                put("packageName", activity.packageName)
            }
            postResult("onDeviceInfoResult", successResponse(mapOf("device" to deviceInfo)))
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceInfo error: ${e.message}", e)
            postResult("onDeviceInfoResult", errorResponse("info_error", e.message ?: "Failed to get device info"))
        }
    }

    override fun getBatteryInfo() {
        try {
            val batteryIntent = activity.registerReceiver(null, android.content.IntentFilter(android.intent.action.BATTERY_CHANGED))
            
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra("level", 0)
                val scale = batteryIntent.getIntExtra("scale", 100)
                val percentage = (level * 100 / scale.toFloat()).toInt()
                val status = batteryIntent.getIntExtra("status", -1)
                val isCharging = status == android.battery.BATTERY_STATUS_CHARGING || 
                                status == android.battery.BATTERY_STATUS_FULL
                
                postResult("onBatteryResult", successResponse(mapOf(
                    "percentage" to percentage,
                    "isCharging" to isCharging,
                    "status" to status
                )))
            } else {
                postResult("onBatteryResult", errorResponse("battery_unavailable", "Battery info unavailable"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBatteryInfo error: ${e.message}", e)
            postResult("onBatteryResult", errorResponse("battery_error", e.message ?: "Failed to get battery info"))
        }
    }

    override fun getNetworkInfo() {
        try {
            val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as 
                android.net.ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo
            
            val isConnected = networkInfo?.isConnected == true
            val networkType = when (networkInfo?.type) {
                android.net.ConnectivityManager.TYPE_WIFI -> "wifi"
                android.net.ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> "unknown"
            }

            postResult("onNetworkResult", successResponse(mapOf(
                "isConnected" to isConnected,
                "networkType" to networkType,
                "isOnline" to isConnected
            )))
        } catch (e: Exception) {
            Log.e(TAG, "getNetworkInfo error: ${e.message}", e)
            postResult("onNetworkResult", errorResponse("network_error", e.message ?: "Failed to get network info"))
        }
    }

    override fun getScreenInfo() {
        try {
            val displayMetrics = activity.resources.displayMetrics
            postResult("onScreenInfoResult", successResponse(mapOf(
                "widthPx" to displayMetrics.widthPixels,
                "heightPx" to displayMetrics.heightPixels,
                "density" to displayMetrics.density,
                "densityDpi" to displayMetrics.densityDpi,
                "scaledDensity" to displayMetrics.scaledDensity,
                "xdpi" to displayMetrics.xdpi,
                "ydpi" to displayMetrics.ydpi
            )))
        } catch (e: Exception) {
            Log.e(TAG, "getScreenInfo error: ${e.message}", e)
            postResult("onScreenInfoResult", errorResponse("screen_error", e.message ?: "Failed to get screen info"))
        }
    }

    // ========== EXTERNAL APPS ==========

    override fun openUrl(url: String) {
        try {
            if (url.isBlank()) {
                postResult("onOpenUrlResult", errorResponse("invalid_url", "URL is required"))
                return
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            postResult("onOpenUrlResult", successResponse(mapOf("url" to url)))
        } catch (e: Exception) {
            Log.e(TAG, "openUrl error: ${e.message}", e)
            postResult("onOpenUrlResult", errorResponse("open_url_error", e.message ?: "Failed to open URL"))
        }
    }

    override fun openDialer(phoneNumber: String) {
        try {
            if (phoneNumber.isBlank()) {
                postResult("onOpenDialerResult", errorResponse("invalid_input", "Phone number is required"))
                return
            }

            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            postResult("onOpenDialerResult", successResponse(mapOf("phoneNumber" to phoneNumber)))
        } catch (e: Exception) {
            Log.e(TAG, "openDialer error: ${e.message}", e)
            postResult("onOpenDialerResult", errorResponse("dialer_error", e.message ?: "Failed to open dialer"))
        }
    }

    override fun openEmail(email: String, subject: String, body: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            postResult("onOpenEmailResult", successResponse(mapOf("email" to email)))
        } catch (e: Exception) {
            Log.e(TAG, "openEmail error: ${e.message}", e)
            postResult("onOpenEmailResult", errorResponse("email_error", e.message ?: "Failed to open email"))
        }
    }

    override fun openSettings(settingsAction: String) {
        try {
            val action = when (settingsAction.uppercase()) {
                "SETTINGS" -> Settings.ACTION_SETTINGS
                "WIFI_SETTINGS" -> Settings.ACTION_WIFI_SETTINGS
                "LOCATION_SETTINGS" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "BLUETOOTH_SETTINGS" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "APPLICATION_DETAILS_SETTINGS" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                "NOTIFICATION_SETTINGS" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                } else {
                    Settings.ACTION_SETTINGS
                }
                else -> Settings.ACTION_SETTINGS
            }

            val intent = Intent(action).apply {
                if (settingsAction.uppercase() == "APPLICATION_DETAILS_SETTINGS" || 
                    settingsAction.uppercase() == "NOTIFICATION_SETTINGS") {
                    data = Uri.parse("package:${activity.packageName}")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            postResult("onOpenSettingsResult", successResponse(mapOf("action" to settingsAction)))
        } catch (e: Exception) {
            Log.e(TAG, "openSettings error: ${e.message}", e)
            postResult("onOpenSettingsResult", errorResponse("settings_error", e.message ?: "Failed to open settings"))
        }
    }

    // ========== PERMISSIONS ==========

    override fun requestPermissions(permissions: String) {
        try {
            val permissionsArray = org.json.JSONArray(permissions)
            val permissionList = mutableListOf<String>()
            
            for (i in 0 until permissionsArray.length()) {
                permissionList.add(permissionsArray.getString(i))
            }

            if (permissionList.isEmpty()) {
                postResult("onPermissionResult", errorResponse("invalid_input", "No permissions specified"))
                return
            }

            requestPermissionsInternal(permissionList)
            // Result will be posted via permission callback
        } catch (e: Exception) {
            Log.e(TAG, "requestPermissions error: ${e.message}", e)
            postResult("onPermissionResult", errorResponse("permission_error", e.message ?: "Failed to request permissions"))
        }
    }

    override fun checkPermission(permission: String) {
        try {
            val isGranted = isPermissionGranted(permission)
            postResult("onPermissionCheckResult", successResponse(mapOf(
                "permission" to permission,
                "granted" to isGranted
            )))
        } catch (e: Exception) {
            Log.e(TAG, "checkPermission error: ${e.message}", e)
            postResult("onPermissionCheckResult", errorResponse("permission_error", e.message ?: "Failed to check permission"))
        }
    }

    /**
     * Handle permission result from runtime permission request
     */
    fun handlePermissionResult(permissions: Array<String>, grantResults: IntArray) {
        val result = JSONObject().apply {
            put("success", true)
            put("error", JSONObject.NULL)
            put("permissions", JSONObject().apply {
                for ((index, permission) in permissions.withIndex()) {
                    put(permission, grantResults[index] == PackageManager.PERMISSION_GRANTED)
                }
            })
        }
        postResult("onPermissionResult", result)
    }
}

// Import View for setFullscreen
import android.view.View
