import json
import urllib.request
from urllib.parse import urljoin
from concurrent.futures import ThreadPoolExecutor

SOURCES = [
    ("https://iptv-org.github.io/iptv/countries/pe.m3u", "Perú • Abierta", "PERÚ"),
    ("https://i.mjh.nz/PlutoTV/es.m3u8", "Pluto TV • FAST", "PLUTO"),
    ("https://i.mjh.nz/Plex/es.m3u8", "Plex TV • FAST", "PLEX")
]

def check_stream(item):
    if item.get("type") == "VOD":
        return item
    try:
        url = item["streamUrl"]
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=5) as r:
            body = r.read(1500).decode("utf-8", errors="ignore")
            if r.status != 200 or "#EXTM3U" not in body:
                return None
            # Verificar que el primer segmento o sub-playlist dentro del m3u8 tambien responda
            for line in body.splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    seg_url = urljoin(url, line)
                    seg_req = urllib.request.Request(seg_url, headers={"User-Agent": "Mozilla/5.0"})
                    with urllib.request.urlopen(seg_req, timeout=5) as sr:
                        if sr.status == 200 and len(sr.read(64)) > 0:
                            return item
                    return None
    except Exception:
        pass
    return None

def fetch_m3u(url, category, badge):
    entries = []
    try:
        raw = urllib.request.urlopen(url, timeout=12).read().decode("utf-8", errors="ignore").splitlines()
        title = ""
        for line in raw:
            line = line.strip()
            if line.startswith("#EXTINF:"):
                title = line.split(",")[-1].strip()
            elif line.startswith("http") and title:
                entries.append({
                    "title": title,
                    "category": category,
                    "type": "TV",
                    "badge": badge,
                    "streamUrl": line
                })
                title = ""
    except Exception:
        pass
    return entries

with open("catalogo.json", encoding="utf-8") as f:
    candidatos = {c["streamUrl"]: c for c in json.load(f)}

for url, cat, badge in SOURCES:
    for entry in fetch_m3u(url, cat, badge):
        if entry["streamUrl"] not in candidatos:
            candidatos[entry["streamUrl"]] = entry

with ThreadPoolExecutor(max_workers=20) as ex:
    verificados = [res for res in ex.map(check_stream, candidatos.values()) if res is not None]

with open("catalogo.json", "w", encoding="utf-8") as f:
    json.dump(verificados, f, indent=2, ensure_ascii=False)

print(f"Catálogo actualizado: {len(verificados)} canales con video real verificado.")
