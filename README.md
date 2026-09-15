# Ultimate HTML WebView

An Android app whose entire UI is provided by a single `assets/index.html` file, with a native Kotlin JavaScript bridge for system capabilities.

## Architecture

APK → WebView → assets/index.html → JavaScript → Android Bridge → Native APIs → Result back to JS

## Build

GitHub Actions builds the APK automatically on every push to `main`.

You can also build locally with:

    ./gradlew assembleDebug

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Bridge API

Documentation coming as features are added.

## Permissions

Internet, storage (legacy), camera, location, notifications, vibrate, record audio.

## Security

- Only explicitly designed methods are exposed to JavaScript.
- Input is validated at the bridge boundary.
- No shell execution, no arbitrary filesystem paths, no reflection.
