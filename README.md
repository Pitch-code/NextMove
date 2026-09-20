# NextMove

**From “later” to handled.**

NextMove is an Android-first action assistant prototype for India. It explores a simple product promise: share a screenshot and turn the important details into one clear, safe next move.

## What this prototype includes

- Calm, custom native Android interface
- English and Hindi language switching
- Correct in-app Back navigation across tabs, samples and voice checks
- First-run guided setup that requests notification, microphone and message-alert access in one sequence
- Contextual notification permission and a visible permission centre
- On-device message-alert scanning (SMS, WhatsApp, email and other chat apps) via Notification Access, with a scam-alert notification and a tap-through explanation of why a message was flagged and what to do
- A pause/resume switch for message scanning, plus a device-only record of recent alerts
- Voice transcription with typed fallback and explicit provider disclosure
- Preliminary on-device scam phrase checks (clearly not live AI)
- India cyber-fraud actions for 1930 and cybercrime.gov.in
- Android share target for images and selected text
- Privacy-safe image-picker demonstration
- Four deterministic sample journeys:
  - Electricity bill
  - Doctor appointment
  - Return deadline
  - Suspicious payment message
- Evidence-backed result cards
- Calendar hand-off with user confirmation
- Device-only handled-item history
- Clear disclosure that live OCR and AI are not connected
- No login, analytics, cloud service, embedded API key or broad inbox/calendar access

## Important prototype boundary

This repository intentionally contains **no live OCR, Gemini, Firebase or internet threat-intelligence integration**. Selected images never leave the phone and are not read by the prototype. Voice transcription is performed by the speech-recognition provider configured on the user’s phone, which may process audio online; NextMove does not save the audio. The preliminary voice result checks only a local list of common scam phrases and explicitly discloses that it did not search the internet.

The future production pipeline is expected to use image-quality checks, OCR with text coordinates, schema-constrained AI extraction, deterministic validation, evidence highlighting, confidence thresholds and explicit user confirmation. It must never claim 100% accuracy.

## Technical structure

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/pitchcode/nextmove/
│   ├── MainActivity.java            # screens, setup flow, transitions and Android intents
│   ├── data/
│   │   ├── HistoryStore.java        # local-only handled history + preferences
│   │   ├── FlaggedStore.java        # local-only record of scam alerts
│   │   └── SampleAnalysis.java      # deterministic prototype scenarios
│   ├── notifications/
│   │   └── NotificationHelper.java  # reminder + high-priority scam-alert channels
│   ├── safety/
│   │   └── VoiceRiskAssessment.java # on-device scam-phrase matcher
│   ├── scan/
│   │   └── MessageScanService.java  # NotificationListenerService message scanner
│   └── ui/
│       └── Design.java              # reusable native design primitives
└── res/
    ├── values/strings.xml           # English
    ├── values-hi/strings.xml        # Hindi
    └── ...                          # theme, colours, icon and locale config
```

The app uses Android framework APIs only. There are no runtime library dependencies beyond the Android SDK.

## Requirements

- JDK 17
- Gradle 9.1+
- Android SDK 36
- Android 8.0 (API 26) or newer device

## Build

With the Android SDK configured:

```bash
gradle :app:assembleDebug
gradle :app:lintDebug
gradle :app:assembleRelease
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Try the experience

1. Install the debug APK on an Android device.
2. Open NextMove.
3. Switch between English and Hindi from the header or Settings.
4. Choose one of the four sample scenarios.
5. Review the summary, extracted evidence and recommended action.
6. Add the item to a calendar or mark it handled.
7. Review the device-only history under Activity.
8. Share an image to NextMove from another Android app to see the safe prototype boundary.

## Message-alert scanning

NextMove checks incoming message notifications for common scam warning phrases and, when it finds them, raises a high-priority alert that opens an explanation with safe next steps and India's 1930 / cybercrime.gov.in actions.

- It uses Android's **Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`), **not** the restricted `READ_SMS` / `RECEIVE_SMS` permissions. This keeps NextMove within Google Play's SMS/Call-Log policy and also lets it see WhatsApp, email and other chat alerts — not just SMS.
- All matching happens on-device using the same local scam-phrase list as the voice check. Nothing is uploaded, and the service cannot read the historical inbox — only new alerts as they arrive.
- Notification Access is a special permission the user grants in system settings; it cannot be requested through a runtime pop-up. The first-run setup flow explains this and links directly to the settings screen.
- Scanning can be paused or turned off at any time from the permission centre, and access can be revoked in system settings.

## Privacy

- The app declares notification and microphone permissions only for their visible features.
- Notification access is requested contextually; Android 12 and earlier have no runtime notification popup.
- Microphone access is requested only after the user chooses voice input.
- Message scanning uses Notification Access with explicit consent; no `READ_SMS`, `RECEIVE_SMS`, call-log, contacts, broad storage, calendar-write or direct-call permission is declared.
- Message content that triggers an alert is stored only in private app preferences on the device, truncated, and can be cleared by the user.
- Calendar events are handed to the user’s calendar app for confirmation.
- Images are selected through Android system pickers, and single messages can still be checked through Android Share.
- No account is required.
- No screenshot is uploaded or analyzed.
- Voice audio is not stored by NextMove; the configured speech provider may process it online.
- Prototype activity is stored in private app preferences.
- App backup and device transfer exclude that local activity.

## Roadmap

1. Validate this interface through the required Google Play closed test.
2. Build a consented, redacted English/Hindi/Hinglish evaluation dataset.
3. Add a private server boundary and abuse controls.
4. Integrate OCR and structured AI extraction behind feature flags.
5. Measure field-level accuracy and abstention before public release.
6. Connect app-owned reminders only after users validate the notification model.
7. Connect live, sourced threat intelligence through a private backend—never through an API key in the app.

## License

Copyright © 2026 Pitch-code. All rights reserved.
