import json
import urllib.request
from urllib.parse import urljoin
from concurrent.futures import ThreadPoolExecutor

SOURCES = [
    ("https://iptv-org.github.io/iptv/countries/pe.m3u", "Perú • Verificado", "PERÚ"),
    ("https://i.mjh.nz/PlutoTV/es.m3u8", "Pluto TV • Verificado", "PLUTO"),
    ("https://i.mjh.nz/Plex/es.m3u8", "Plex TV • Verificado", "PLEX")
]

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://www.google.com/"
}

def verify_url_deep(url, depth=0):
    if depth > 2:
        return False
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=6) as r:
            if r.status != 200:
                return False
            data = r.read(4096)
            if not data:
                return False
            text = data.decode("utf-8", errors="ignore")
            if "#EXTM3U" in text:
                for line in text.splitlines():
                    line = line.strip()
                    if line and not line.startswith("#"):
                        next_url = urljoin(r.geturl(), line)
                        return verify_url_deep(next_url, depth + 1)
                return False
            return len(data) >= 512
    except Exception:
        return False

def check_stream(item):
    if item.get("type") == "VOD":
        return item
    return item if verify_url_deep(item["streamUrl"]) else None

def fetch_m3u(url, category, badge):
    entries = []
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        raw = urllib.request.urlopen(req, timeout=12).read().decode("utf-8", errors="ignore").splitlines()
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

print(f"Catálogo limpio: {len(verificados)} canales con video real verificado.")
