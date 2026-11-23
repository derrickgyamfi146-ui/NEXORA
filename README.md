# NEXORA

**NEXORA – Share Without Limits**

NEXORA is an Android-to-Android file-sharing app that uses **Wi-Fi Direct** to move files between nearby phones. The transfer itself does not require mobile data or an internet connection.

## Current Android app features

- Start a receiving session and share its QR code with nearby phones.
- Join a receiver's Wi-Fi Direct group by scanning its NEXORA QR code.
- Group sharing: multiple phones can join one receiver session.
- Select and send multiple files from Android's secure file picker.
- Browse local photos, videos, music and installed apps.
- Prepare installed apps for sharing, including their split APK parts where present.
- Pause, resume and cancel supported incoming and outgoing transfers.
- View transfer progress and the Sent / Received lists in the Transfer Centre.
- Preview supported received media, open files with installed apps, rename or delete received files, and install received APKs through Android's installer.
- Save received files under `Download/NEXORA/` on Android 10 and newer.
- Browse folders through Android's Storage Access Framework.
- Dark/light theme preference and editable device display name.
- A foreground service keeps active transfers running while navigating away from the main screen.

## Android compatibility

- The app package supports Android 7.0 (API 24) and newer.
- **NEXORA's QR-based Wi-Fi Direct sharing currently requires Android 10 (API 29) or newer** on both phones. Older phones can open the app, but cannot start or join this QR transfer flow.
- Wi-Fi Direct behavior varies by phone manufacturer and Android version. Hotspot or other Wi-Fi features may conflict on some devices.

## Build

The APK is built by the GitHub Actions workflow in `.github/workflows/nexora-apk.yml`.

- Current audit build: **0.8.6** (versionCode **83**).
- Application ID: `com.nexora.share`.
- Keep the existing installation and its data when installing an update. Android only permits an in-place update when the APK has the same application ID and signing certificate.

## Current limitations and testing still needed

- Every supported phone model still needs real-device testing, including two-phone connection, group joining, screen-off transfers, large files, pause/resume, cancellation and recovery after a disconnect.
- The transfer protocol does not yet provide verified end-to-end file checksums or resume-from-the-middle after an interrupted transfer.
- The receiver does not yet provide a robust pre-transfer storage-capacity check for very large incoming files.
- Wi-Fi Direct QR sessions are intended for nearby trusted devices; session expiry, rotating QR credentials and a persistent trusted-device list are not yet implemented.
- PC and iPhone interoperability are not currently supported.
- A successful GitHub Actions build confirms compilation and signing checks; it does not prove that real-device transfers work on every phone.

## Development direction

Prioritise transfer reliability and storage safety first, then stronger transfer verification and recovery, followed by richer file management and carefully selected features. Preserve the current NEXORA design and branding while improving reliability.
