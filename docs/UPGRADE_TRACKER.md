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

- [ ] 1. Full source audit: connection lifecycle, socket protocol, permissions, service lifecycle, navigation, transfer history and build/signing workflow.
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
