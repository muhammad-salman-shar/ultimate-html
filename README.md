# WebView Android App

A native Android application that uses a WebView to display HTML/CSS/JavaScript frontend while providing powerful system capabilities through a secure JavaScript bridge.

## 🎯 Core Concept

This is **NOT** an HTML-to-APK builder. This is a single Android APK with a fixed `index.html` file bundled in assets. The Android native layer provides system capabilities to the HTML through a secure JavaScript bridge.

```
                 ANDROID APK
                     │
          ┌──────────┴──────────┐
          │                     │
   Native Android Layer     WebView Layer
          │                     │
          │               index.html
          │                     │
          │              HTML + CSS + JS
          │                     │
          └──── JavaScript ─────┘
                 Bridge
```

## 📁 Project Structure

```
repository/
│
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/webviewapp/
│   │       │   ├── MainActivity.kt         # Main activity with WebView
│   │       │   ├── JavaScriptBridge.kt     # Bridge interface definition
│   │       │   └── WebViewBridge.kt        # Bridge implementation
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml
│   │       │   ├── values/
│   │       │   │   ├── strings.xml
│   │       │   │   └── colors.xml
│   │       │   └── xml/
│   │       │       └── file_paths.xml      # FileProvider paths
│   │       ├── assets/
│   │       │   └── index.html              # Application frontend
│   │       └── AndroidManifest.xml
│   │
│   ├── build.gradle.kts
│   └── proguard-rules.pro
│
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
└── README.md
```

## 🚀 Quick Start

### Prerequisites

- Android Studio Arctic Fox (2020.3.1) or newer
- JDK 17 or higher
- Android SDK with API 34
- Minimum SDK: 24 (Android 7.0)

### Build Commands

```bash
# Clean and build debug APK
./gradlew clean assembleDebug

# Build release APK
./gradlew clean assembleRelease

# Install on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

### Output APK Location

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`

## 🔗 JavaScript Bridge API

The bridge is exposed to JavaScript via two interfaces:

1. **`Android`** - Direct method calls to native Android
2. **`AndroidBridge`** - Callback receiver for async results

### Calling Convention

All bridge methods are asynchronous. Results are returned via callbacks:

```javascript
// Call a native method
Android.someMethod(param1, param2);

// Receive result via callback
window.AndroidBridge.onSomeResult = function(result) {
    if (result.success) {
        console.log('Success:', result.data);
    } else {
        console.error('Error:', result.error);
    }
};

// Or listen via CustomEvent
window.addEventListener('onSomeResult', (e) => {
    const result = e.detail;
    console.log('Result:', result);
});
```

### Response Format

All callbacks receive a JSON object:

**Success:**
```json
{
    "success": true,
    "error": null,
    "data": { ... }
}
```

**Error:**
```json
{
    "success": false,
    "error": {
        "type": "error_type",
        "message": "Error description"
    }
}
```

---

## 📖 API Reference

### File Operations

#### `Android.pickFile(mimeType, allowMultiple)`

Open Android file picker to select files.

| Parameter | Type | Description |
|-----------|------|-------------|
| mimeType | String | MIME type filter (e.g., `"image/*"`, `"*/*"`, `"application/pdf"`) |
| allowMultiple | String | `"true"` or `"false"` for multiple selection |

**Callback:** `AndroidBridge.onFilePickResult(result)`

```javascript
Android.pickFile('image/*', 'false');

window.AndroidBridge.onFilePickResult = function(result) {
    // result.files = [{ uri, name, type, size }, ...]
};
```

---

#### `Android.saveFile(filename, content, mimeType)`

Save a file to app's internal storage.

| Parameter | Type | Description |
|-----------|------|-------------|
| filename | String | Name of the file (no paths allowed) |
| content | String | Base64-encoded file content |
| mimeType | String | MIME type of the file |

**Callback:** `AndroidBridge.onFileSaveResult(result)`

```javascript
const base64Content = btoa('Hello World!');
Android.saveFile('test.txt', base64Content, 'text/plain');

window.AndroidBridge.onFileSaveResult = function(result) {
    // result.filename, result.path, result.size
};
```

---

#### `Android.openFile(filename, mimeType)`

Open a saved file using Android's intent system.

| Parameter | Type | Description |
|-----------|------|-------------|
| filename | String | Name of previously saved file |
| mimeType | String | MIME type of the file |

**Callback:** `AndroidBridge.onFileOpenResult(result)`

---

#### `Android.download(url, filename, mimeType)`

Download a file using Android DownloadManager.

| Parameter | Type | Description |
|-----------|------|-------------|
| url | String | URL to download from |
| filename | String | Name to save as |
| mimeType | String | MIME type (optional) |

**Callback:** `AndroidBridge.onDownloadResult(result)`

```javascript
Android.download('https://example.com/file.pdf', 'download.pdf', 'application/pdf');
```

---

### Sharing

#### `Android.shareText(text, title)`

Share text via Android Sharesheet.

| Parameter | Type | Description |
|-----------|------|-------------|
| text | String | Text content to share |
| title | String | Optional title for share intent |

**Callback:** `AndroidBridge.onShareResult(result)`

---

#### `Android.shareFile(filename, mimeType)`

Share a saved file via Android Sharesheet.

| Parameter | Type | Description |
|-----------|------|-------------|
| filename | String | Name of saved file |
| mimeType | String | MIME type of the file |

**Callback:** `AndroidBridge.onShareResult(result)`

---

### Notifications

#### `Android.notify(title, body, channelId)`

Show a native Android notification.

| Parameter | Type | Description |
|-----------|------|-------------|
| title | String | Notification title |
| body | String | Notification body text |
| channelId | String | Optional channel ID (uses default if empty) |

**Callback:** `AndroidBridge.onNotificationResult(result)`

```javascript
Android.notify('Hello', 'This is a notification', '');
```

---

#### `Android.cancelNotification(notificationId)`

Cancel a notification by ID.

| Parameter | Type | Description |
|-----------|------|-------------|
| notificationId | Number | ID of notification to cancel |

---

### Clipboard

#### `Android.copyToClipboard(text)`

Copy text to clipboard.

| Parameter | Type | Description |
|-----------|------|-------------|
| text | String | Text to copy |

**Callback:** `AndroidBridge.onClipboardResult(result)`

---

#### `Android.readFromClipboard()`

Read text from clipboard.

**Callback:** `AndroidBridge.onClipboardResult(result)`

```javascript
Android.readFromClipboard();

window.AndroidBridge.onClipboardResult = function(result) {
    // result.text contains clipboard content
};
```

---

### Device Interaction

#### `Android.vibrate(durationMs)`

Vibrate the device.

| Parameter | Type | Description |
|-----------|------|-------------|
| durationMs | Number | Duration in milliseconds |

---

#### `Android.setFullscreen(enable)`

Toggle fullscreen/immersive mode.

| Parameter | Type | Description |
|-----------|------|-------------|
| enable | String | `"true"` to enable, `"false"` to disable |

---

#### `Android.keepScreenOn(enable)`

Keep screen on or allow sleep.

| Parameter | Type | Description |
|-----------|------|-------------|
| enable | String | `"true"` to keep on, `"false"` to allow sleep |

---

### Camera / Media

#### `Android.takePhoto()`

Launch camera to take a photo.

**Callback:** `AndroidBridge.onCameraResult(result)`

```javascript
Android.takePhoto();

window.AndroidBridge.onCameraResult = function(result) {
    // result.imageData contains base64 image
};
```

---

#### `Android.recordVideo()`

Launch video recording.

**Callback:** `AndroidBridge.onCameraResult(result)`

---

### Location

#### `Android.getLocation()`

Get current device location. Requires location permissions.

**Callback:** `AndroidBridge.onLocationResult(result)`

```javascript
Android.getLocation();

window.AndroidBridge.onLocationResult = function(result) {
    // result.latitude, result.longitude, result.accuracy
};
```

---

### Device Information

#### `Android.getDeviceInfo()`

Get device information.

**Callback:** `AndroidBridge.onDeviceInfoResult(result)`

```javascript
Android.getDeviceInfo();

// Returns: manufacturer, model, brand, androidVersion, sdkVersion, appVersion, packageName
```

---

#### `Android.getBatteryInfo()`

Get battery information.

**Callback:** `AndroidBridge.onBatteryResult(result)`

```javascript
Android.getBatteryInfo();

// Returns: percentage, isCharging, status
```

---

#### `Android.getNetworkInfo()`

Get network connectivity status.

**Callback:** `AndroidBridge.onNetworkResult(result)`

```javascript
Android.getNetworkInfo();

// Returns: isConnected, networkType, isOnline
```

---

#### `Android.getScreenInfo()`

Get screen dimensions and density.

**Callback:** `AndroidBridge.onScreenInfoResult(result)`

```javascript
Android.getScreenInfo();

// Returns: widthPx, heightPx, density, densityDpi, scaledDensity
```

---

### External Applications

#### `Android.openUrl(url)`

Open URL in external browser.

| Parameter | Type | Description |
|-----------|------|-------------|
| url | String | URL to open |

**Callback:** `AndroidBridge.onOpenUrlResult(result)`

---

#### `Android.openDialer(phoneNumber)`

Open phone dialer with number.

| Parameter | Type | Description |
|-----------|------|-------------|
| phoneNumber | String | Phone number to dial |

**Callback:** `AndroidBridge.onOpenDialerResult(result)`

---

#### `Android.openEmail(email, subject, body)`

Open email composer.

| Parameter | Type | Description |
|-----------|------|-------------|
| email | String | Email address |
| subject | String | Email subject |
| body | String | Email body text |

**Callback:** `AndroidBridge.onOpenEmailResult(result)`

---

#### `Android.openSettings(settingsAction)`

Open Android settings.

| Parameter | Type | Description |
|-----------|------|-------------|
| settingsAction | String | Action: `"SETTINGS"`, `"WIFI_SETTINGS"`, `"LOCATION_SETTINGS"`, `"BLUETOOTH_SETTINGS"`, `"APPLICATION_DETAILS_SETTINGS"`, `"NOTIFICATION_SETTINGS"` |

**Callback:** `AndroidBridge.onOpenSettingsResult(result)`

---

### Permissions

#### `Android.requestPermissions(permissions)`

Request runtime permissions.

| Parameter | Type | Description |
|-----------|------|-------------|
| permissions | String | JSON array of permission strings |

**Callback:** `AndroidBridge.onPermissionResult(result)`

```javascript
Android.requestPermissions(JSON.stringify([
    'android.permission.CAMERA',
    'android.permission.READ_EXTERNAL_STORAGE'
]));

window.AndroidBridge.onPermissionResult = function(result) {
    // result.permissions = { 'android.permission.CAMERA': true, ... }
};
```

---

#### `Android.checkPermission(permission)`

Check if a permission is granted.

| Parameter | Type | Description |
|-----------|------|-------------|
| permission | String | Permission string to check |

**Callback:** `AndroidBridge.onPermissionCheckResult(result)`

---

## 🔐 Security Considerations

The JavaScript bridge is designed with security in mind:

1. **Explicit Method Exposure**: Only explicitly defined methods are exposed via `@JavascriptInterface`. No reflection or arbitrary code execution.

2. **Input Validation**: All parameters from JavaScript are validated before use.

3. **Path Traversal Prevention**: Filename parameters are sanitized to prevent directory traversal attacks.

4. **Content URI Usage**: Files are shared using secure `content://` URIs via FileProvider.

5. **No Shell Execution**: The bridge does not expose any shell execution capabilities.

6. **Scoped Storage**: Respects Android's scoped storage requirements (API 29+).

7. **Permission Model**: Runtime permissions are properly requested and handled.

---

## 📋 Permissions

The following permissions are declared in `AndroidManifest.xml`:

| Permission | Purpose | Protection Level |
|------------|---------|------------------|
| `INTERNET` | Network access for WebView and downloads | Normal |
| `ACCESS_NETWORK_STATE` | Check network connectivity | Normal |
| `READ_EXTERNAL_STORAGE` | Read files (API ≤32) | Dangerous |
| `WRITE_EXTERNAL_STORAGE` | Write files (API ≤28) | Dangerous |
| `CAMERA` | Take photos/video | Dangerous |
| `ACCESS_FINE_LOCATION` | Precise location | Dangerous |
| `ACCESS_COARSE_LOCATION` | Approximate location | Dangerous |
| `VIBRATE` | Vibrate device | Normal |
| `POST_NOTIFICATIONS` | Show notifications (API 33+) | Dangerous |

Dangerous permissions require runtime user consent.

---

## 🧪 Testing the Application

### Manual Testing Checklist

1. **WebView Loading**
   - [ ] App launches and displays index.html
   - [ ] Bridge status shows "Yes ✓"

2. **File Operations**
   - [ ] Pick file opens file picker
   - [ ] Save file creates file in app storage
   - [ ] Open file launches file viewer
   - [ ] Download starts and completes

3. **Sharing**
   - [ ] Share text opens Sharesheet
   - [ ] Share file opens Sharesheet with file

4. **Notifications**
   - [ ] Show notification creates notification
   - [ ] Cancel notification removes it

5. **Clipboard**
   - [ ] Copy text copies to clipboard
   - [ ] Read clipboard retrieves text

6. **Device Interaction**
   - [ ] Vibrate triggers vibration
   - [ ] Fullscreen toggles immersive mode
   - [ ] Keep screen on prevents sleep

7. **Camera**
   - [ ] Take photo launches camera
   - [ ] Record video launches video recorder

8. **Location**
   - [ ] Get location returns coordinates (with permission)

9. **Device Info**
   - [ ] Device info returns correct data
   - [ ] Battery info shows percentage
   - [ ] Network info shows connection status
   - [ ] Screen info shows dimensions

10. **External Apps**
    - [ ] Open browser launches browser
    - [ ] Open dialer launches phone app
    - [ ] Open email launches email client
    - [ ] Open settings launches settings

11. **Permissions**
    - [ ] Request permissions shows dialog
    - [ ] Check permission returns correct status

---

## 🛠️ Development

### Modifying index.html

The `index.html` file in `app/src/main/assets/` contains the complete frontend. You can:

- Edit HTML structure
- Modify CSS styles
- Update JavaScript logic
- Add new bridge method calls

All HTML, CSS, and JavaScript remain in the same file.

### Adding New Bridge Methods

1. Add method signature to `JavaScriptBridge.kt` interface
2. Implement method in `WebViewBridge.kt`
3. Add callback handler in `AndroidBridgeHelper` class
4. Update `index.html` with new functionality

### Debugging

Enable WebView debugging in `MainActivity.kt`:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
    WebView.setWebContentsDebuggingEnabled(true)
}
```

Then use Chrome DevTools: `chrome://inspect/#devices`

---

## 📦 Building for Release

1. **Create Keystore** (if you don't have one):
```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-alias
```

2. **Configure Signing** in `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../my-release-key.jks")
            storePassword = "your-store-password"
            keyAlias = "my-alias"
            keyPassword = "your-key-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

3. **Build Release APK**:
```bash
./gradlew clean assembleRelease
```

---

## ⚠️ Known Limitations

### Android Version Differences

- **API 29+**: Scoped storage limits direct filesystem access
- **API 30+**: Package visibility restrictions
- **API 33+**: `POST_NOTIFICATIONS` permission required

### WebView Limitations

- Some modern web APIs may not be available in older Android versions
- WebGL support varies by device
- Service Workers have limited support

### Permission Restrictions

- Background location requires additional justification
- Some permissions may be restricted by device manufacturers

---

## 📄 License

This project is provided as-is for educational and development purposes.

---

## 🤝 Support

For issues or questions:

1. Check this README for API documentation
2. Review the demo `index.html` for usage examples
3. Check Android logs via `adb logcat`
4. Use Chrome DevTools for WebView debugging