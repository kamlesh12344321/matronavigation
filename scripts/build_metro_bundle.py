#!/usr/bin/env python3
"""
Builds the offline metro-line bundle that ships inside the Metro Navigator APK.

The app must work with no network and even when the Overpass API is down, so the metro lines
of the supported cities are fetched from OpenStreetMap at BUILD time (by a developer, not by
the phone) and stored gzipped in app/src/main/assets/metro/<city>.json.gz.

The file content is the raw Overpass response trimmed to what the app's parser reads, so the
phone parses it with exactly the same code it uses for live downloads (MetroRouteService).

Usage:
    python3 scripts/build_metro_bundle.py                 # all cities in CITIES
    python3 scripts/build_metro_bundle.py delhi mumbai    # only these
Re-run whenever lines open or change, then rebuild the APK. Data (c) OpenStreetMap
contributors, ODbL: keep the attribution shown in the app.
"""
import gzip
import json
import os
import sys
import time
import urllib.parse
import urllib.request

# name: (lat, lon, radius_m). Radius covers the whole network incl. suburbs.
CITIES = {
    "delhi_ncr": (28.6139, 77.2090, 45000),
    "mumbai": (19.0760, 72.8777, 40000),
    "bengaluru": (12.9716, 77.5946, 30000),
    "kolkata": (22.5726, 88.3639, 30000),
    "chennai": (13.0827, 80.2707, 30000),
    "hyderabad": (17.3850, 78.4867, 30000),
    "kochi": (9.9816, 76.2999, 20000),
    "ahmedabad_gandhinagar": (23.1200, 72.6000, 35000),
    "pune": (18.5204, 73.8567, 25000),
    "lucknow": (26.8467, 80.9462, 20000),
    "jaipur": (26.9124, 75.7873, 20000),
    "nagpur": (21.1458, 79.0882, 20000),
    "kanpur": (26.4499, 80.3319, 20000),
    "agra": (27.1767, 78.0081, 20000),
    "bhopal": (23.2599, 77.4126, 20000),
    "indore": (22.7196, 75.8577, 20000),
    "meerut": (28.9845, 77.7064, 20000),
    "surat": (21.1702, 72.8311, 20000),
    "patna": (25.5941, 85.1376, 20000),
}

SERVERS = [
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass-api.de/api/interpreter",
]
USER_AGENT = "TestGPSLocation-MetroNavigator-bundle/1.0"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "metro")


def query(lat, lon, radius):
    # Same as MetroRouteService.buildOverpassQuery(relationsOnly = true).
    around = f"(around:{radius},{lat},{lon})"
    return (f'[out:json][timeout:300];'
            f'rel["route"~"^(subway|light_rail|monorail)$"]{around}->.r;.r out geom;'
            f'node(r.r)["name"];out body;')


def fetch(q):
    data = urllib.parse.urlencode({"data": q}).encode()
    last = None
    for attempt in range(3):
        for url in SERVERS:
            try:
                req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=320) as r:
                    body = r.read().decode("utf-8")
                if '"remark"' in body and "runtime error" in body:
                    last = f"{url}: server timeout"
                    continue
                return json.loads(body)
            except Exception as e:  # noqa: BLE001 - try the next server
                last = f"{url}: {e}"
        time.sleep(10 * (attempt + 1))
    raise RuntimeError(last)


def r6(v):
    return round(v, 6)


def trim(doc):
    """Keep only the fields MetroRouteService.parseAll reads; round coordinates to ~10 cm."""
    keep_tags = {"name", "name:en", "ref", "from", "to", "route", "railway", "public_transport"}
    out = []
    for el in doc.get("elements", []):
        t = el.get("type")
        tags = {k: v for k, v in (el.get("tags") or {}).items() if k in keep_tags}
        if t == "node":
            if "lat" not in el:
                continue
            out.append({"type": "node", "id": el["id"], "lat": r6(el["lat"]), "lon": r6(el["lon"]), "tags": tags})
        elif t == "relation":
            members = []
            for m in el.get("members", []):
                mm = {"type": m.get("type"), "ref": m.get("ref"), "role": m.get("role", "")}
                if m.get("type") == "way" and m.get("geometry"):
                    mm["geometry"] = [{"lat": r6(p["lat"]), "lon": r6(p["lon"])} for p in m["geometry"] if p]
                members.append(mm)
            out.append({"type": "relation", "id": el["id"], "tags": tags, "members": members})
    return {"elements": out}


def main():
    wanted = sys.argv[1:] or list(CITIES)
    os.makedirs(OUT_DIR, exist_ok=True)
    failed = []
    for name in wanted:
        if name not in CITIES:
            print(f"unknown city {name}; known: {', '.join(CITIES)}")
            failed.append(name)
            continue
        lat, lon, radius = CITIES[name]
        print(f"{name}: downloading…", flush=True)
        try:
            doc = trim(fetch(query(lat, lon, radius)))
        except Exception as e:  # noqa: BLE001
            print(f"{name}: FAILED ({e})")
            failed.append(name)
            continue
        rels = sum(1 for e in doc["elements"] if e["type"] == "relation")
        if rels == 0:
            print(f"{name}: no metro relations found, skipped")
            continue
        doc["bundle"] = {"city": name, "lat": lat, "lon": lon, "radius": radius,
                         "generated": time.strftime("%Y-%m-%d"), "source": "OpenStreetMap contributors (ODbL)"}
        path = os.path.join(OUT_DIR, f"{name}.json.gz")
        with gzip.open(path, "wt", encoding="utf-8", compresslevel=9) as f:
            json.dump(doc, f, separators=(",", ":"), ensure_ascii=False)
        print(f"{name}: {rels} line relations, {os.path.getsize(path) // 1024} KB")
    if failed:
        print("failed:", ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
