"""
Pull NOAA MRMS multi-sensor (radar + gauge) precipitation for one point and keep a
running history in mrms.json. Runs hourly in GitHub Actions.

Data: s3://noaa-mrms-pds (public, no key). Values are liquid-equivalent, mm -> inches.
Coordinates come from the HOME_LAT / HOME_LON repository secrets. The output is encrypted
(AES-256-GCM, key = SHA-256 of the DATA_KEY secret) because the repo is public; the app
decrypts it with the same key. Nothing location-related is printed to the (public) logs.

DRY_RUN=true uses a public test point, prints values and writes nothing.
"""
import datetime as dt
import base64
import gzip
import hashlib
import json
import os
import sys
import tempfile
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

import numpy as np
import pygrib

BUCKET = "https://noaa-mrms-pds.s3.amazonaws.com"
TZ = ZoneInfo("America/Chicago")
DRY_RUN = os.environ.get("DRY_RUN", "").lower() == "true"
if DRY_RUN:
    LAT, LON, DATA_KEY = 40.63, -89.33, None   # public test point (town centre already in index.html)
else:
    try:
        LAT = float(os.environ["HOME_LAT"])
        LON = float(os.environ["HOME_LON"])
        DATA_KEY = os.environ["DATA_KEY"].strip()
        assert DATA_KEY
    except (KeyError, ValueError, AssertionError):
        raise SystemExit("Add the HOME_LAT, HOME_LON and DATA_KEY repository secrets first.")
# Never write or print the coordinates (or anything derived from them): the data file and Actions logs are public.
RESET = os.environ.get("RESET", "").lower() == "true"
OUT = sys.argv[1] if len(sys.argv) > 1 else "mrms.json"
BACKFILL_FROM = os.environ.get("BACKFILL_FROM", "").strip()
P24 = "MultiSensor_QPE_24H_Pass2_00.00"
P1H = ["MultiSensor_QPE_01H_Pass2_00.00", "MultiSensor_QPE_01H_Pass1_00.00"]
NS = {"s3": "http://s3.amazonaws.com/doc/2006-03-01/"}
_list_cache = {}
_logged_grid = False


def get(url, tries=3):
    for n in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=90) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            if n == tries - 1:
                raise
            print(f"  retry {url}: {e}")


def list_day(product, yyyymmdd):
    """All object keys for one product and UTC day."""
    key = (product, yyyymmdd)
    if key in _list_cache:
        return _list_cache[key]
    keys, token = [], None
    while True:
        q = {"list-type": "2", "prefix": f"CONUS/{product}/{yyyymmdd}/"}
        if token:
            q["continuation-token"] = token
        root = ET.fromstring(get(f"{BUCKET}/?{urllib.parse.urlencode(q)}"))
        keys += [c.find("s3:Key", NS).text for c in root.findall("s3:Contents", NS)]
        if root.findtext("s3:IsTruncated", namespaces=NS) == "true":
            token = root.findtext("s3:NextContinuationToken", namespaces=NS)
        else:
            break
    _list_cache[key] = keys
    return keys


def find_key(product, end_utc):
    stamp = end_utc.strftime("%Y%m%d-%H0000")
    for k in list_day(product, end_utc.strftime("%Y%m%d")):
        if stamp in k:
            return k
    return None


def point_value(key):
    """Inches at our point, or None if missing/no coverage."""
    global _logged_grid
    raw = gzip.decompress(get(f"{BUCKET}/{key}"))
    with tempfile.NamedTemporaryFile(suffix=".grib2") as f:
        f.write(raw)
        f.flush()
        g = pygrib.open(f.name).message(1)
        lat1 = g["latitudeOfFirstGridPointInDegrees"]
        lon1 = g["longitudeOfFirstGridPointInDegrees"]
        dlat = g["jDirectIncrementInDegrees"]
        dlon = g["iDirectIncrementInDegrees"]
        jpos = g["jScansPositively"]
        nj, ni = g["Nj"], g["Ni"]
        i = int(round(((LON % 360) - lon1) / dlon))
        j = int(round((LAT - lat1) / dlat)) if jpos else int(round((lat1 - LAT) / dlat))
        if not _logged_grid:
            print(f"  grid: lat1={lat1} lon1={lon1} dlat={dlat} dlon={dlon} jpos={jpos} {nj}x{ni} (point inside grid: {0 <= i < ni and 0 <= j < nj})")
            _logged_grid = True
        if not (0 <= i < ni and 0 <= j < nj):
            raise SystemExit("Point is outside the MRMS CONUS grid")
        v = g.values[j, i]
        if np.ma.is_masked(v) or v is None or v < 0:
            return None
        return round(float(v) / 25.4, 3)


def local_midnight_utc(day):
    """UTC time of the end of a local calendar day (next local midnight)."""
    end = dt.datetime.combine(day + dt.timedelta(days=1), dt.time(0), TZ)
    return end.astimezone(dt.timezone.utc)


def _key():
    return hashlib.sha256(DATA_KEY.encode()).digest()


def load():
    if DRY_RUN:
        return {}
    try:
        with open(OUT) as f:
            env = json.load(f)
    except FileNotFoundError:
        return {}
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    try:
        plain = AESGCM(_key()).decrypt(base64.b64decode(env["n"]), base64.b64decode(env["c"]), None)
    except Exception:  # noqa: BLE001  (key changed -> start fresh)
        print("Could not decrypt existing data (key changed?) - starting fresh")
        return {}
    return json.loads(plain)


def save(data):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    nonce = os.urandom(12)
    ct = AESGCM(_key()).encrypt(nonce, json.dumps(data, separators=(",", ":")).encode(), None)
    with open(OUT, "w") as f:
        json.dump({"v": 1, "alg": "AES-256-GCM", "n": base64.b64encode(nonce).decode(),
                   "c": base64.b64encode(ct).decode(), "updated": data["updated"]}, f)


def main():
    data = load()
    if RESET:
        data = {}  # location changed: start over
    days = data.setdefault("days", {})
    hourly = data.setdefault("hourly", {})

    now = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0)
    today = now.astimezone(TZ).date()

    # 1) Hourly totals for the last 48 h (upgrade Pass1 -> Pass2 when available)
    for h in range(48, 0, -1):
        end = now - dt.timedelta(hours=h - 1)
        k = end.strftime("%Y-%m-%dT%H:00Z")
        if k in hourly and hourly[k][1] == 2:
            continue
        for n, prod in enumerate(P1H):
            key = find_key(prod, end)
            if key:
                v = point_value(key)
                hourly[k] = [v if v is not None else 0.0, 2 - n]
                break
    cutoff = (now - dt.timedelta(hours=72)).strftime("%Y-%m-%dT%H:00Z")
    hourly = {k: v for k, v in sorted(hourly.items()) if k >= cutoff}
    data["hourly"] = hourly

    # 2) Daily totals (local calendar days) from the 24 h gauge-corrected product
    start = today - dt.timedelta(days=4)
    if BACKFILL_FROM:
        start = min(start, dt.date.fromisoformat(BACKFILL_FROM))
    d = start
    fetched = 0
    while d < today:
        ds = d.isoformat()
        if not (ds in days and days[ds][1] == "24h"):
            key = find_key(P24, local_midnight_utc(d))
            if key:
                v = point_value(key)
                days[ds] = [v if v is not None else 0.0, "24h"]
                fetched += 1
                if fetched % 50 == 0:
                    print(f"  ...{fetched} days")
        d += dt.timedelta(days=1)

    # 3) Today so far (and any recent day still missing its 24 h file) from hourly sums
    for back in range(0, 3):
        day = today - dt.timedelta(days=back)
        ds = day.isoformat()
        if back > 0 and ds in days and days[ds][1] == "24h":
            continue
        lo = local_midnight_utc(day - dt.timedelta(days=1))
        hi = local_midnight_utc(day)
        hrs = [v[0] for k, v in hourly.items()
               if lo < dt.datetime.strptime(k, "%Y-%m-%dT%H:00Z").replace(tzinfo=dt.timezone.utc) <= hi]
        if hrs:
            days[ds] = [round(sum(hrs), 3), "hourly"]

    data["days"] = dict(sorted(days.items()))
    data["updated"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    latest = max(hourly) if hourly else None
    data["latestHour"] = latest
    print(f"days={len(days)} hourly={len(hourly)} latestHour={latest} fetchedDaily={fetched}")
    if DRY_RUN:
        for ds in sorted(days)[-6:]:
            print(f"  {ds}: {days[ds]}")
        print("  last hours:", list(hourly.items())[-6:])
        return
    save(data)


if __name__ == "__main__":
    main()
