# NextMove

**From “later” to handled.**

NextMove is an Android-first action assistant prototype for India. It explores a simple product promise: share a screenshot and turn the important details into one clear, safe next move.

## What this prototype includes

- Calm, custom native Android interface
- English and Hindi language switching
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
- No login, analytics, network access, cloud service or API key

## Important prototype boundary

This repository intentionally contains **no live OCR, Gemini or Firebase integration**. Selected images never leave the phone and are not read by the prototype. The sample analyzer exists to validate product language, interaction design, accessibility and trust before paid infrastructure is introduced.

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

- No account is required.
- No network permission is declared.
- No image is uploaded or analyzed.
- Prototype activity is stored in private app preferences.
- App backup and device transfer exclude that local activity.

## Roadmap

1. Validate this interface through the required Google Play closed test.
2. Build a consented, redacted English/Hindi/Hinglish evaluation dataset.
3. Add a private server boundary and abuse controls.
4. Integrate OCR and structured AI extraction behind feature flags.
5. Measure field-level accuracy and abstention before public release.
6. Add notifications only after users validate the action model.

## License

Copyright © 2026 Pitch-code. All rights reserved.
