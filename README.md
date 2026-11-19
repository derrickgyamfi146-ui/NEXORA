# NEXORA

**NEXORA – Share Without Limits**

NEXORA is an Android-to-Android local file-sharing app built with Google Nearby Connections. It is designed to transfer files directly between nearby devices without requiring mobile data for the transfer itself.

## Current Android app features

- Nearby phone discovery and secure connection-code confirmation.
- Send and receive files using the Android system file picker.
- Select multiple files in one batch.
- Sequential outgoing transfer queue.
- Transfer percentage, average speed and estimated remaining time.
- Foreground service notification intended to keep transfers active when the app is backgrounded.
- In-app Photos, Videos and Music library lists with search and newest/name sorting.
- Documents and all-files access through Android's secure picker.
- Received files saved under `Download/NEXORA/` on Android 10 and newer.
- Received-file open, rename and delete actions.
- Dark/light theme preference and editable device display name.

## Build

The Android APK is built by the GitHub Actions workflow in `.github/workflows/android-build.yml`.

The application ID remains `com.nexora.share`. Keep the existing installation and its data until an update has been verified on the device. Android only permits an in-place update when the APK is signed with the same signing certificate.

## Current limitations

- Transfers and background behavior still require real-device testing with two phones, including screen-off and large-file tests.
- The media browser currently lists local photos, videos and audio; documents and arbitrary files use Android's system picker.
- The app uses the phone's available apps to open received media; a dedicated built-in music/video player is not included yet.
- Automatic extraction/sharing of installed apps, full phone migration, PC/iPhone interoperability, social-media downloading and video-to-audio conversion are not included.
- Android storage and background-execution rules vary by OS version and manufacturer. A successful APK build does not prove every device-specific behavior.

## Development direction

The planned sequence is transfer reliability, richer media/file management, transfer history and preferences, then cross-platform capabilities where Android and other platform rules allow them.
