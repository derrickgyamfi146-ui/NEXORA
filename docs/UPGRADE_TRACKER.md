# NEXORA Master Upgrade Tracker

Last updated: 2026-11-20 (Group Share, queue and release build)

Release target: NEXORA 0.8.3, versionCode 80 (upgrading from 0.8.2/build79)  
Application ID: `com.nexora.share`

**Release protection:** preserve the existing application ID and original signing certificate. Never tell the user to uninstall the installed NEXORA app. Only provide a new APK after the latest integrated build, signing check and artifact upload all pass.

## Ordered upgrade checklist

| # | Work item | Current status |
|---:|---|---|
| 1 | Full source audit: Activity, transport, service, permissions, manifest, UI and CI workflow | **Complete** |
| 2 | Persistent two-way connection/session architecture | **Implemented; physical-device verification pending** |
| 3 | Repeated sending in either direction without rescanning | **Implemented in framed protocol; physical-device verification pending** |
| 4 | Connect first without selecting files; select and send later | **UI path available; verify with new session on devices** |
| 5 | Incoming transfer manager with progress, pause, resume and cancel | **Implemented in protocol v3: metadata-first queue, per-item queued cancellation, active-item pause/resume/cancel and queue status UI; physical verification pending** |
| 6 | Separate Sent and Received item lists | **Implemented; physical-device verification pending** |
| 7 | Combined chronological History | **Implemented for new transfers and legacy records; migration date/deduplication correction committed** |
| 8 | Verified connection navigation, both devices leave QR screen, explicit disconnect | **Implemented; physical-device verification pending** |
| 9 | One connection animation per session; upright receiver-only rocket | **Implemented; visual verification pending** |
| 10 | Five-second splash, background preload and reduce screen blinking | **Implemented; device verification pending** |
| 11 | Wi-Fi/hotspot prompts only after relevant failure | **Implemented; device verification pending** |
| 12 | Group Share: one QR, multiple active peers, broadcast and recipient exclusions | **Implemented in code: one Wi-Fi Direct group QR accepts multiple TCP peers; group owner can broadcast to connected peers and exclude selected recipients; physical multi-device verification pending** |
| 13 | Floating control draggable across full app screen | **Implemented; touch-boundary verification pending** |
| 14 | Full visual redesign using yellow/red/blue direction inspired by 2C TV icon | **Code consistency pass complete across Home, Receive, Media, Files, Contacts, Transfer Centre, Sent, Received, History and Settings; visual review on devices pending** |
| 15 | Final build, original signing-certificate check, APK verification and device test plan | **Final 0.8.3/build80 workflow running; original signing-key fingerprint must match and artifact upload must pass** |

## Technical decisions and safeguards

- The new Wi-Fi Direct protocol marker is `NEXORA-WFD-3`. Both phones must use the upgraded NEXORA version; older protocol builds are not compatible with the metadata-first queue and multi-peer handshake.
- The duplex session uses one inbound reader per socket, framed file batches, acknowledgements, and a send-turn request/grant/release handshake. Only one device sends payload data at a time; either device can request the next turn.
- Incoming batches send all file metadata before file bytes, allowing the receiver to display the full queue. Each queued item can be cancelled without closing the connection; the active item can be paused, resumed or cancelled. Queue state is in-memory and does not resume after a disconnected session.
- Group Share uses the Wi-Fi Direct group owner as the broadcaster. Multiple phones join the same QR session; the group owner sends a batch to each selected peer in sequence and may exclude selected peer IDs. Actual peer capacity depends on Android device/vendor Wi-Fi Direct support.
- Android controls Wi-Fi Direct and hotspot behaviour. NEXORA can detect failures and open system settings, but cannot silently switch hotspot off.
- The startup splash remains visible for five seconds even if installed-app discovery finishes earlier. App discovery runs on a background thread.
- The service remains bound while temporary scanner/system activities are open to avoid rebuilding the whole UI when returning.
- The CI workflow refuses to create a replacement signing key. It checks the known original certificate fingerprint against both the restored key and the produced APK.

## Verification log

| Commit | Change verified | CI result |
|---|---|---|
| `9f529c9` | Failure-driven Wi-Fi readiness prompt | Build, signature and artifact upload passed |
| `6c5715a` | Five-second splash | Build, signature and artifact upload passed |
| `442e954` | Sent, Received and combined History | Build, signature and artifact upload passed |
| `832111d` | Main yellow/red/blue visual theme | Build, signature and artifact upload passed |
| `183508e` | Corrected splash colour type and integrated receiver-role animation code | Build, signature and artifact upload passed |
| `3cb997e` | Incoming manager pause/resume/cancel controls | Build, signature and artifact upload passed |
| `6e86a5e` | Initial framed duplex send/receive session | Build, signature and artifact upload passed |
| `324768b` | Protocol v2 marker | Build, signature and artifact upload passed |
| `60726f9` | Disconnect cleanup and wake pending sends | Build, signature and artifact upload passed |\n| `569e750` | Import legacy sent and received items into History | Build, signature and artifact upload passed |\n| `352b130` | Correct year rollover and avoid duplicate legacy History events | CI pending at the time of tracker update |

Recent screen redesign commits: Transfer Centre (`7ddd7c0`), Received Files (`af727ba`), History (`5d21707`), Sent Files (`e075c9f`), Settings (`90bacf2`) and Receive (`0d8e896`). The later consistency pass adjusted Files/media and Contacts navigation (`0c5a0d9`, `1a937db`). Queue and Group Share transport work is in commits `ee06a92`, `cf6b8fad`, `744fe367`, `8a72742`, `9dcb95c` and subsequent fixes. Builds `8a72742` and `485c80a` passed compilation, original signing-certificate verification and artifact upload. Release build 80 is now running with the final version number and artifact name. CI proves compilation and signing, not real-world Wi-Fi Direct operation.

## Release acceptance tests

Do not mark the upgrade finished until these tests pass on the actual Android devices:

1. Connect Phone A and Phone B with no files selected.
2. Send an app from A to B, then send a different app from B to A without rescanning.
3. Send multiple files in repeated batches over the same connection.
4. Pause, resume and cancel an incoming item; confirm cancellation does not close the connection.
5. Turn the screen off during a transfer and confirm it continues or reports a recoverable failure.
6. Disconnect from the Transfer Centre and confirm both devices return to a disconnected state.
7. Verify only the receiving phone shows the upright connection rocket, and neither phone replays it after each file.
8. Verify the splash stays visible for five seconds and the app does not blink when returning from the QR scanner.
9. Verify Wi-Fi/hotspot guidance appears only after the relevant failure.
10. Verify floating control can move vertically and horizontally throughout the app area.
11. Start Receive on one phone, scan the same QR from at least two other phones, confirm they both appear as connected, broadcast one batch to all, then repeat while excluding one selected phone.
12. Install the final APK as an update over the existing installation; do not uninstall the old app.
