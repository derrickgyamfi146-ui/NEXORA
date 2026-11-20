# NEXORA Master Upgrade Tracker

Last updated: 2026-11-20
Baseline: NEXORA 0.8.2 (versionCode 79), applicationId `com.nexora.share`
Rule: preserve the existing applicationId and original signing certificate. Do not instruct the user to uninstall the installed app.

## User-approved target behaviour

- Establish a persistent connection before selecting any files.
- Once connected, either phone can send and receive repeatedly over the same session.
- The connection remains open until a user explicitly disconnects or the connection is genuinely lost.
- The receiver has an Incoming manager showing live transfers with progress and per-item pause/resume/cancel controls; completed items remain available in Received.
- Both devices enter the connected dashboard only after the transport handshake succeeds.
- Connection animation runs once per new connection, not after each file; upright rocket appears only on receiving device.
- Sender navigation waits for the connection animation; receiver leaves its QR screen after verified connection.
- Startup splash lasts about five seconds while essential preparation happens; do not add a second blocking ad-loading screen.
- Wi-Fi/hotspot guidance is shown in response to a real setup failure, with the specific cause and relevant settings action; do not prompt about hotspot prematurely.
- Group Share: one join QR, multiple participants, any member may send, sender can exclude selected recipients.
- Floating control is draggable across the full app content area, in both axes.
- Redesign the UI with a yellow/red/blue palette inspired by the visual direction of the 2C TV icon, without copying its logo.
- Sent and Received screens show actual sent/received items; History is a combined chronological record.
- Provide explicit Disconnect and confirmation when leaving an active session.

## Ordered checklist

- [x] 1. Full source audit: connection lifecycle, socket protocol, permissions, service lifecycle, navigation, transfer history and build/signing workflow.
- [ ] 2. Persistent two-way connection/session architecture.
- [ ] 3. Repeated bidirectional transfers without reconnecting.
- [ ] 4. Connect-first workflow with no file selection required.
- [ ] 5. Incoming transfer manager: live queue, progress, pause, resume, cancel, completed list.
- [ ] 6. Redesign Sent and Received views as actual item lists.
- [ ] 7. Combined chronological History view.
- [ ] 8. Verified-connection navigation on both devices and explicit disconnect flow.
- [ ] 9. Connection animation: once per connection; upright receiver-only rocket; correct timing.
- [ ] 10. Five-second splash, background preparation and eliminate unintended Activity restarts/blinking.
- [ ] 11. Failure-driven Wi-Fi readiness diagnostics and actionable prompts.
- [ ] 12. Group Share with multi-device routing, recipient selection/exclusion and group lifecycle.
- [ ] 13. Fully draggable floating control, constrained to app screen.
- [ ] 14. Full visual redesign using yellow/red/blue palette and consistent components.
- [ ] 15. Build, signature continuity verification, automated checks, then real-device test checklist.

## Initial audit findings (2026-11-20)

1. `WifiDirectTransport` currently implements a one-way protocol: the group-owner side accepts and receives files; the joining side initiates the socket and sends files. The accepted socket is not exposed as a symmetric send/receive session. This is the main blocker for the requested bridge.
2. The socket protocol has no transfer IDs, direction-neutral message frames, queue model, or resumable chunk offsets. Current receiver writes directly to MediaStore while reading the socket, so per-item pause/resume/cancel cannot be implemented reliably as a UI-only change.
3. `TransferKeepAliveService` reports at most one Wi-Fi Direct endpoint and a single connected boolean. Group Share requires a participant/session model and a multi-recipient transfer layer.
4. `MainActivity` triggers the rocket from connection callbacks on both peers, and also has a second received-file animation tied to the literal “All files received successfully.” status. Animation and navigation need a single connection-state owner, separated from transfer-completion events.
5. The QR flow creates a group and displays credentials, while the readiness helper currently prompts about hotspot settings before there is evidence that hotspot state caused a failure. The prompt must move to diagnostic failure handling.
6. Existing Sent and Received views are separate lists under a “History” heading; there is no true combined chronological history.
7. The current floating control is constrained to the content frame, and its vertical drag is therefore limited to that frame. A redesigned overlay must remain within the app window and avoid covering essential controls.
8. The startup splash already uses an approximately 1.5–2.1 second animation and a delayed transition; its loading work and navigation should be measured before changing timing.
9. The current workflow builds and verifies an APK, but certificate continuity must be explicitly compared and enforced before distributing each update.

## Implementation and verification log

No upgrade feature has been marked complete yet. The initial audit is underway. Build success alone does not count as device-level verification.

| Date | Work | Result |
|---|---|---|
| 2026-11-20 | Read baseline Activity, transport, service, manifest, Gradle and CI workflow | Initial architecture findings recorded; full audit remains open |

## Audit completion note

The source inventory and all Kotlin app classes, app manifest, Gradle configuration, launcher icon vectors and both GitHub Actions workflows have now been inspected. The audit confirms that the requested upgrade is not a cosmetic-only change: the current transport is structurally one-way and must be redesigned before the requested user interface can honestly claim two-way or group sharing. The CI signing step currently prints historical/current fingerprints but does not itself fail on a mismatch; this must be hardened before a new APK is distributed.


## Follow-up implementation work

- Removed the full-screen received-file rocket replay from the "All files received successfully" status handler. The connection animation must be tied to a connection event, never to every completed transfer. This is a targeted correction; the full animation-role redesign remains open.
- Hardened the APK workflow: it now refuses to generate a replacement signing key when the original key is unavailable, verifies the restored signing certificate against the known NEXORA certificate fingerprint, and verifies the built APK against the same fingerprint before artifact upload. This protects update installation instead of silently producing an APK signed with a different key.
- Signing-workflow validation run: pending at the time of this tracker update.
- Step 2 remains in progress. The new duplex protocol must have one inbound frame reader per socket, correlated acknowledgements, synchronized writes, and independent send/receive queues. The current one-way transport remains in place until the duplex implementation is integrated and compiled.

- Changed Wi-Fi readiness behaviour: Wi-Fi-off is still checked before attempting a session, but hotspot is no longer presented as a presumed problem before connection. Hotspot guidance now appears only after a Wi-Fi Direct group/join/socket failure that suggests a radio conflict, and only while Wi-Fi itself is enabled.
- Build validation for the code changes is running in GitHub Actions; no APK from these intermediate commits is approved for installation.

- Extended the startup splash to 5 seconds. The installed-app library is already preloaded on a background thread, so this keeps the visual intro running while that preparation proceeds. This is an initial timing change; visual polish and ad/loading integration remain to be reviewed.

## Additional progress (2026-11-20)

- [x] Targeted animation correction: removed the post-transfer rocket replay. The connection animation is no longer intentionally triggered by the "All files received successfully" status.
- [x] Startup timing: splash now lasts approximately 5 seconds; installed-app discovery remains on a background thread.
- [x] Wi-Fi failure diagnostics: removed the premature hotspot confirmation; show hotspot guidance only after a relevant Wi-Fi Direct failure while Wi-Fi is enabled.
- [x] History/navigation groundwork: added distinct Sent, Received and combined History destinations. New successful send/receive events are recorded in a shared timestamped event list and shown newest-first in History. This is UI/history groundwork; legacy transfer entries and live Incoming queue integration still need further work.
- [x] CI checks for commits 9f529c9, 6c5715a and 78edc95 completed successfully, including the original signing-key fingerprint check and APK certificate compatibility check.
- [ ] The newest Sent/Received/History navigation commit is currently being built and has not yet passed CI.
- [ ] The persistent duplex transfer architecture remains the next major implementation task. The old transport has not been replaced, so two-way send/receive is not complete.

- [x] Added separate Sent and Received navigation destinations and a dedicated combined History screen. Successful send and receive events are timestamped and shown newest-first in History. The latest CI run for this code reached APK upload; final job completion is still pending.
- [x] Updated the launcher icon artwork to use NEXORA's yellow/red/blue direction while retaining its own NEXORA symbol.
- [x] Applied the yellow/red/blue direction to the main app background, top header, navigation strip, selected navigation chip, cards and shared button styling. This is the first visual-theme pass; screen-by-screen spacing, typography, and component redesign remains open.
- [ ] Incoming transfer queue with pause/resume/cancel is not implemented yet.
- [ ] Persistent bidirectional and group transfer protocols are not implemented yet.
