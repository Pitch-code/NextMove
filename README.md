# NextMove

**From “later” to handled.**

NextMove is an Android-first action assistant prototype for India. It explores a simple product promise: share a screenshot and turn the important details into one clear, safe next move.

## What this prototype includes

- Calm, custom native Android interface
- English and Hindi language switching
- Correct in-app Back navigation across tabs, samples and voice checks
- Contextual notification permission and a visible permission centre
- Voice transcription with typed fallback and explicit provider disclosure
- Preliminary on-device scam phrase checks (clearly not live AI)
- India cyber-fraud actions for 1930 and cybercrime.gov.in
- Android share target for images
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
│   ├── MainActivity.java            # screens, transitions and Android intents
│   ├── data/
│   │   ├── HistoryStore.java        # local-only handled history
│   │   └── SampleAnalysis.java      # deterministic prototype scenarios
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

## Privacy

- The app declares notification and microphone permissions only for their visible features.
- Notification access is requested contextually; Android 12 and earlier have no runtime notification popup.
- Microphone access is requested only after the user chooses voice input.
- No SMS, call-log, contacts, broad storage, calendar-write or direct-call permission is declared.
- Calendar events are handed to the user’s calendar app for confirmation.
- Messages and images are selected through Android Share and system pickers.
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
