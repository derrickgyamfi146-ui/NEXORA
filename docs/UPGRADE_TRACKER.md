# NEXORA Master Upgrade Tracker

Last updated: 2026-11-20 (latest UI consistency pass)

Baseline: NEXORA 0.8.2, versionCode 79  
Application ID: `com.nexora.share`

**Release protection:** preserve the existing application ID and original signing certificate. Never tell the user to uninstall the installed NEXORA app. Only provide a new APK after the latest integrated build, signing check and artifact upload all pass.

## Ordered upgrade checklist

| # | Work item | Current status |
|---:|---|---|
| 1 | Full source audit: Activity, transport, service, permissions, manifest, UI and CI workflow | **Complete** |
| 2 | Persistent two-way connection/session architecture | **Implemented; physical-device verification pending** |
| 3 | Repeated sending in either direction without rescanning | **Implemented in framed protocol; physical-device verification pending** |
| 4 | Connect first without selecting files; select and send later | **UI path available; verify with new session on devices** |
| 5 | Incoming transfer manager with progress, pause, resume and cancel | **Partially implemented: one active incoming item at a time; queued-item list and per-item controls remain** |
| 6 | Separate Sent and Received item lists | **Implemented; physical-device verification pending** |
| 7 | Combined chronological History | **Implemented for new transfers and legacy records; migration date/deduplication correction committed** |
| 8 | Verified connection navigation, both devices leave QR screen, explicit disconnect | **Implemented; physical-device verification pending** |
| 9 | One connection animation per session; upright receiver-only rocket | **Implemented; visual verification pending** |
| 10 | Five-second splash, background preload and reduce screen blinking | **Implemented; device verification pending** |
| 11 | Wi-Fi/hotspot prompts only after relevant failure | **Implemented; device verification pending** |
| 12 | Group Share: one QR, multiple active peers, broadcast and recipient exclusions | **Not implemented; current transport is single-peer and must be redesigned before UI can claim group support** |
| 13 | Floating control draggable across full app screen | **Implemented; touch-boundary verification pending** |
| 14 | Full visual redesign using yellow/red/blue direction inspired by 2C TV icon | **Partially complete: Transfer Centre, Sent, Received, History, Settings and Receive redesigned; Home/Media/Files/Contacts received a consistency pass; visual review still pending** |
| 15 | Final build, original signing-certificate check, APK verification and device test plan | **Not started for release 0.8.3/build80; prior intermediate builds passed CI only** |

## Technical decisions and safeguards

- The new Wi-Fi Direct protocol marker is `NEXORA-WFD-2`. Both phones must use the upgraded NEXORA version; old protocol builds are intentionally not treated as compatible.
- The duplex session uses one inbound reader per socket, framed file batches, acknowledgements, and a send-turn request/grant/release handshake. Only one device sends payload data at a time; either device can request the next turn.
- Incoming pause uses TCP back-pressure. Cancel drains and discards only the current item, then negatively acknowledges it so the peer can continue with the next item. This is not yet a multi-item resumable queue.
- Group Share is deliberately not exposed as a working feature yet. The current transport supports one active peer session, not multi-peer routing or broadcast.
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

Recent screen redesign commits also passed their APK CI workflows: Transfer Centre (`7ddd7c0`), Received Files (`af727ba`), History (`5d21707`), Sent Files (`e075c9f`), Settings (`90bacf2`) and Receive (`0d8e896`). A later consistency pass adjusted the Files/media palette and Files browse card (`0c5a0d9`) and corrected the Contacts selection navigation (`1a937db`); the latest CI run is still in progress. CI proves compilation and signing, not real-world Wi-Fi Direct operation.

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
11. Test Group Share separately after multi-peer routing and recipient exclusion are implemented.
12. Install the final APK as an update over the existing installation; do not uninstall the old app.
