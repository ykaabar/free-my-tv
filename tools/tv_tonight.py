#!/usr/bin/env python3
"""Tonight on French TV, for the home screen's "Tonight" (run by .github/workflows/tv.yml).

Reads the free XMLTV guide of the TNT (xmltvfr.fr, from the racacax/XML-TV-Fr project), keeps the evening's main
programme of a few channels and writes a small JSON (a few KB) that the TVs read instead of the whole guide (tens of MB).
With TMDB_KEY in the environment, each programme gets TMDB's backdrop when TMDB knows it (else the guide's own image).

  python3 tools/tv_tonight.py out/tonight.json            # today, Paris time
  python3 tools/tv_tonight.py out/tonight.json --guide guide.xml   # from a file already downloaded
"""
import datetime as dt
import gzip
import io
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from zoneinfo import ZoneInfo

CHANNELS = ["TF1", "France 2", "M6", "Arte", "TMC", "W9"]   # in this order on the cards
GUIDES = ["https://xmltvfr.fr/xmltv/xmltv_tnt.xml.gz", "https://xmltvfr.fr/xmltv/xmltv_tnt.zip",
          "https://xmltvfr.fr/xmltv/xmltv_tnt.xml"]
PARIS = ZoneInfo("Europe/Paris")
PRIME_FROM, PRIME_TO = (20, 40), (22, 0)   # the evening's programme starts in this window...
MIN_LENGTH = 25 * 60                        # ...and lasts this long at least (not the weather or an ad break)
UA = {"User-Agent": "FreeMyTV (github.com; tonight's TV for a home screen)"}


def norm(s):
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]", "", s)


def fetch(url, timeout=120):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read()


def guide_bytes():
    last = None
    for url in GUIDES:
        try:
            data = fetch(url)
        except Exception as e:  # noqa: BLE001 - try the next format
            last = e
            continue
        if data[:2] == b"\x1f\x8b":
            return gzip.decompress(data)
        if data[:2] == b"PK":
            z = zipfile.ZipFile(io.BytesIO(data))
            name = next(n for n in z.namelist() if n.endswith(".xml"))
            return z.read(name)
        return data
    raise SystemExit(f"no guide: {last}")


def when(s):
    """XMLTV time "20261122211000 +0100" → aware datetime."""
    s = s.strip()
    base = dt.datetime.strptime(s[:14], "%Y%m%d%H%M%S")
    m = re.search(r"([+-])(\d\d)(\d\d)$", s)
    if not m:
        return base.replace(tzinfo=dt.timezone.utc)
    off = dt.timedelta(hours=int(m.group(2)), minutes=int(m.group(3)))
    return base.replace(tzinfo=dt.timezone(off if m.group(1) == "+" else -off))


def text(el, tag):
    found = el.find(tag)
    return (found.text or "").strip() if found is not None and found.text else ""


def tonight(xml_bytes, day):
    wanted = {norm(c): c for c in CHANNELS}
    ids = {}   # XMLTV channel id → our channel name
    progs = {c: [] for c in CHANNELS}
    for _, el in ET.iterparse(io.BytesIO(xml_bytes), events=("end",)):
        if el.tag == "channel":
            cid = el.get("id", "")
            names = [norm(d.text) for d in el.findall("display-name")] + [norm(cid.rsplit(".", 1)[0])]
            for n in names:
                if n in wanted and wanted[n] not in ids.values():
                    ids[cid] = wanted[n]
                    break
            el.clear()
        elif el.tag == "programme":
            ch = ids.get(el.get("channel", ""))
            if ch:
                start, stop = when(el.get("start")), when(el.get("stop", el.get("start")))
                local = start.astimezone(PARIS)
                if local.date() == day:
                    icon = el.find("icon")
                    cats = [c.text.strip() for c in el.findall("category") if c.text]
                    progs[ch].append({
                        "start": start, "stop": stop,
                        "title": text(el, "title"), "sub": text(el, "sub-title"),
                        "desc": text(el, "desc")[:280], "cat": cats[0] if cats else "",
                        "year": text(el, "date")[:4], "icon": icon.get("src", "") if icon is not None else "",
                    })
            el.clear()
    out = []
    for ch in CHANNELS:
        best = None
        for p in sorted(progs[ch], key=lambda p: p["start"]):
            t = p["start"].astimezone(PARIS)
            long_enough = (p["stop"] - p["start"]).total_seconds() >= MIN_LENGTH
            if PRIME_FROM <= (t.hour, t.minute) <= PRIME_TO and long_enough:
                best = p
                break
        if best is None:   # nothing starts in the window: what's on at 21:15
            at = dt.datetime.combine(day, dt.time(21, 15), PARIS)
            best = next((p for p in progs[ch] if p["start"] <= at < p["stop"]), None)
        if best:
            best["ch"] = ch
            out.append(best)
    return out, sorted(set(ids.values()))


def tmdb(path, key, **params):
    params["language"] = "fr-FR"
    headers = dict(UA, Accept="application/json")
    if key.startswith("eyJ"):
        headers["Authorization"] = "Bearer " + key
    else:
        params["api_key"] = key
    url = "https://api.themoviedb.org/3" + path + "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=20) as r:
        return json.load(r)


def match(p, key):
    """TMDB's film or show for the programme, only when the titles agree: no picture beats a wrong one."""
    cat = norm(p["cat"])
    kinds = ["movie", "tv"] if "film" in cat else ["tv", "movie"]
    for kind in kinds:
        params = {"query": p["title"]}
        if kind == "movie" and p["year"]:
            params["year"] = p["year"]
        try:
            results = tmdb(f"/search/{kind}", key, **params).get("results", [])
        except Exception as e:  # noqa: BLE001 - a programme without a picture is fine
            print(f"TMDB {p['title']}: {e}", file=sys.stderr)
            return None
        for r in results[:5]:
            names = {norm(r.get("title" if kind == "movie" else "name")),
                     norm(r.get("original_title" if kind == "movie" else "original_name"))}
            if norm(p["title"]) in names and r.get("backdrop_path"):
                return {"tmdb": r["id"], "kind": kind, "backdrop": r["backdrop_path"]}
    return None


def main():
    args = sys.argv[1:]
    if not args:
        raise SystemExit(__doc__)
    target = args[0]
    xml_bytes = open(args[args.index("--guide") + 1], "rb").read() if "--guide" in args else guide_bytes()
    day = dt.datetime.now(PARIS).date()
    progs, found = tonight(xml_bytes, day)
    missing = [c for c in CHANNELS if c not in found]
    if missing:
        print("channels not in the guide:", ", ".join(missing), file=sys.stderr)
    key = os.environ.get("TMDB_KEY", "").strip()
    items = []
    for p in progs:
        item = {"ch": p["ch"], "start": int(p["start"].timestamp() * 1000), "stop": int(p["stop"].timestamp() * 1000),
                "title": p["title"], "sub": p["sub"], "cat": p["cat"], "desc": p["desc"], "year": p["year"]}
        found_on_tmdb = match(p, key) if key and p["title"] else None
        if found_on_tmdb:
            item.update(found_on_tmdb)
        elif p["icon"].startswith("https://"):
            item["backdrop"] = p["icon"]
        items.append(item)
        print(f'{p["ch"]:9} {p["start"].astimezone(PARIS):%H:%M}  {p["title"]}  '
              f'({"TMDB" if found_on_tmdb else "guide image" if "backdrop" in item else "no image"})')
    if not items:
        raise SystemExit("nothing for tonight: the guide's channels or times changed")
    os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        json.dump({"date": day.isoformat(), "made": dt.datetime.now(PARIS).isoformat(timespec="minutes"),
                   "items": items}, f, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
