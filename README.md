# TestGPSLocation — Metro Navigator & GPS Logger

Two Android apps sharing one library:

| Module | App | What it does |
|---|---|---|
| `:app` | **Metro Navigator** | Position underground with no GPS and no network, the way Google Maps does it — free, on the phone, no backend: metro lines of the current city (map constraint), inertial speed with zero-velocity updates, an inertial odometer that identifies every stop's station, self-learnt Wi-Fi and mobile-cell fingerprints with handover checkpoints (stations and tunnels), Wi-Fi RTT, per-run speed memory, gyroscope curve checks, step counting, and a Hidden Markov Model that fuses them on the track. Logs every position to a new file each time logging starts. |
| `:gpslogger` | **GPS Logger** | Writes every GPS fix (time, lat, lon, accuracy) to a new file each time logging starts and shows it in a list. |
| `:core` | library | `GpsPoint`, `GeoToolbox`, `NetworkStatus`, `TelemetryLogger`. |

## Quick start

```bash
./gradlew assembleDebug          # build both apps
./gradlew testDebugUnitTest      # 104 unit, component, test-drive and long-tunnel simulation tests (Metro Navigator)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r gpslogger/build/outputs/apk/debug/gpslogger-debug.apk
```

Logs: `Download/TestGPSLocation/<App>_telemetry_<yyyy-MM-dd_HH-mm-ss>.txt` on the phone — a new file
each time logging starts (named after that moment).

## Metro data for your current city, anywhere in the world

The first time the phone has a position and any network in a city, the app automatically saves
that city's whole metro network (30 km, OpenStreetMap, © OpenStreetMap contributors, ODbL) on
the phone; navigation underground then needs no network. A small safety-net bundle (18 Indian
cities, 336 KB, `app/src/main/assets/metro/`) covers a first visit with no network at all.
Add or refresh bundled cities at build time:

```bash
python3 scripts/build_metro_bundle.py            # all cities in the script
python3 scripts/build_metro_bundle.py pune       # one city
```

The live Overpass download in the app is only an optional refresh.

## Documentation — Metro Navigator (current)

| Document | Formats |
|---|---|
| High-Level Design v2.0 | [MD](docs/metro_navigator/01_High_Level_Design.md) · [DOCX](docs/metro_navigator/01_High_Level_Design.docx) · [PDF](docs/metro_navigator/01_High_Level_Design.pdf) |
| Technical Design v2.0 (algorithms, constants, tests, tuning) | [MD](docs/metro_navigator/02_Technical_Design.md) · [DOCX](docs/metro_navigator/02_Technical_Design.docx) · [PDF](docs/metro_navigator/02_Technical_Design.pdf) |
| Technology Guide (plain language: every technology, when and how it is used) | [MD](docs/metro_navigator/03_Technology_Guide.md) · [DOCX](docs/metro_navigator/03_Technology_Guide.docx) · [PDF](docs/metro_navigator/03_Technology_Guide.pdf) |
| **NH-3779 requirements, status, remaining work, investigations** | [MD](docs/metro_navigator/04_NH-3779_Requirements_and_Status.md) · [DOCX](docs/metro_navigator/04_NH-3779_Requirements_and_Status.docx) · [PDF](docs/metro_navigator/04_NH-3779_Requirements_and_Status.pdf) |
| Everything in one download | [docs/metro_navigator/Metro_Navigator_Documentation.zip](docs/metro_navigator/Metro_Navigator_Documentation.zip) |

Regenerate DOCX/PDF after editing the Markdown: `pip install markdown && python3 scripts/build_docs.py`.

Earlier documents covering both apps (v1.0, before radio positioning and bundled data) remain
in `docs/` for reference; for Metro Navigator the documents above supersede them.

Status: code complete and simulation-tested; **validate on real underground rides before
production** (HLD §12).
