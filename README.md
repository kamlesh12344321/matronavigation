# TestGPSLocation — Metro Navigator & GPS Logger

Two Android apps sharing one library:

| Module | App | What it does |
|---|---|---|
| `:app` | **Metro Navigator** | GPS navigation that keeps working underground with no GPS and no network: stored metro lines + motion sensors + station-stop detection. Logs every position to a daily file. |
| `:gpslogger` | **GPS Logger** | Writes every GPS fix (time, lat, lon, accuracy) to a daily file and shows it in a list. |
| `:core` | library | `GpsPoint`, `GeoToolbox`, `NetworkStatus`, `TelemetryLogger`. |

## Quick start

```bash
./gradlew assembleDebug          # build both apps
./gradlew testDebugUnitTest      # 39 unit + ride-simulation tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r gpslogger/build/outputs/apk/debug/gpslogger-debug.apk
```

Logs: `Download/TestGPSLocation/<App>_telemetry_<yyyy-MM-dd>.txt` on the phone.

## Documentation

| Document | Formats |
|---|---|
| High-Level Design | [MD](docs/01_High_Level_Design.md) · [DOCX](docs/01_High_Level_Design.docx) · [PDF](docs/01_High_Level_Design.pdf) |
| Technical Design (algorithms, constants, edge cases, tests, field-test & tuning guide) | [MD](docs/02_Technical_Design.md) · [DOCX](docs/02_Technical_Design.docx) · [PDF](docs/02_Technical_Design.pdf) |
| Everything in one download | [docs/TestGPSLocation_Documentation.zip](docs/TestGPSLocation_Documentation.zip) |

Status: code complete and simulation-tested; **validate on a real underground ride before production** (Technical Design §13).
