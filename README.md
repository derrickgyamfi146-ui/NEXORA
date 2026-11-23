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
- Resume an interrupted file from its saved byte position: reconnect and select the same file again. Files already completed in an interrupted batch are checksum-verified and skipped rather than saved twice.
- View transfer progress and the Sent / Received lists in the Transfer Centre.
- View per-recipient transfer history, including completed, failed, cancelled and interrupted items.
- Manage remembered trusted devices: allow, block or forget a device.
- QR admission codes expire after 10 minutes. The receiver refreshes the QR and rotates the admission token without disconnecting phones already connected.
- Foreground notifications reflect active sending, receiving and paused states, with progress where available.
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

- Current integrated build: **0.8.9** (versionCode **86**).
- Application ID: `com.nexora.share`.
- Keep the existing installation and its data when installing an update. Android only permits an in-place update when the APK has the same application ID and signing certificate.
- Both phones must use the same NEXORA protocol version. Build 0.8.9 uses protocol marker `NEXORA-WFD-6`.

## Reliability and privacy notes

- NEXORA calculates a SHA-256 checksum before sending and verifies the received bytes before making a file visible in Downloads.
- Interrupted incoming bytes are stored in app-private storage. If the connection drops, reconnect and select the same file again to resume. The receiver verifies the saved prefix and final file; partial files older than 30 days are cleaned up.
- Completed files from an interrupted multi-file batch are checked against their stored SHA-256 checksum and skipped if the saved copy still matches.
- The receiver checks available storage before each incoming file and keeps a 16 MB safety reserve. It does not reserve space for an entire multi-file batch in advance.
- Trusted devices are remembered using an app-install ID and can be allowed, blocked or forgotten. This is a local identity mechanism, not hardware-backed device attestation; clearing NEXORA's app data creates a new device identity.
- QR codes expire after 10 minutes and refresh automatically. Both phones must be on the same build/protocol to connect.
- PC and iPhone interoperability are not currently supported.

## Testing still needed

Real-device testing is still required on the target Android phones, including two-phone connection, group joining, large files, interrupted-transfer resume, duplicate skipping, trusted-device blocking, screen-off transfers, pause/resume, cancellation and recovery after a disconnect. A successful GitHub Actions build confirms compilation and signing checks; it does not prove that Wi-Fi Direct transfers work on every phone.

Preserve the current NEXORA design and branding while improving reliability.
