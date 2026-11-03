# TestGPSLocation — Metro Navigator & GPS Logger

Two Android apps sharing one library:

| Module | App | What it does |
|---|---|---|
| `:app` | **Metro Navigator** | Position underground with no GPS and no network — on the phone, no backend, nothing learnt or stored between rides. Source order: GPS → network location (Google fused: Wi-Fi and cell towers) → dead reckoning on the metro line (inertial speed with zero-velocity updates, stop and departure detection, snapping to each station the train stops at, an inertial odometer that identifies the station, gyroscope curve checks). Without a line: dead reckoning along the gyroscope heading. Logs every position (and the serving mobile cell) to a new file each time logging starts, and (menu switch) sends its position to Nemo Handy as the phone's location while there is no GPS. |
| `:gpslogger` | **GPS Logger** | Writes every GPS fix (time, lat, lon, accuracy) to a new file each time logging starts and shows it in a list. |
| `:core` | library | `GpsPoint`, `GeoToolbox`, `NetworkStatus`, `TelemetryLogger`. |

## Quick start

```bash
./gradlew assembleDebug          # build both apps
./gradlew testDebugUnitTest      # 92 unit, edge-case, Handy-feed, test-drive and long-tunnel simulation tests
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

## Documentation — Metro Navigator

**Current status:** [docs/Metro_Navigator_POC_What_Works.pdf](docs/Metro_Navigator_POC_What_Works.pdf)
(what works, what cannot be achieved, edge cases measured, steps before production).

**Outdated:** the documents below and `docs/Metro_Navigator_Code_Flow_Guide.pdf` still describe the
self-learning Wi-Fi / cell fingerprints, ride memory, Bluetooth beacons and step counting, all since
removed.

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

Status: code complete and simulation-tested (390 simulated rides across 13 situations, plus road
cases); **not yet validated on a real metro ride — do that before production**.
