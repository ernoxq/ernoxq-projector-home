"""Finds Apple's lighter HEVC version of each screensaver flight and checks that it is reachable.
Run on GitHub (the projector's list is assets/aerials.json); writes aerials-scan.json."""
import io, json, ssl, tarfile, urllib.request

# Apple signs sylvan.apple.com with its own CA; the projector itself uses plain http for these files.
CTX = ssl._create_unverified_context()

UA = {"User-Agent": "Mozilla/5.0"}
def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120, context=CTX).read()
def head(url):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=UA, method="HEAD"), timeout=30, context=CTX)
        return r.status, int(r.headers.get("Content-Length") or 0)
    except Exception as e:
        return str(e), 0

assets = []
for feed in ["https://sylvan.apple.com/Aerials/resources-16.tar", "https://sylvan.apple.com/Aerials/resources-15.tar", "https://sylvan.apple.com/Aerials/resources-13.tar"]:
    try:
        t = tarfile.open(fileobj=io.BytesIO(get(feed)))
        e = json.load(t.extractfile("entries.json"))
        assets += e.get("assets", [])
        print(feed, len(e.get("assets", [])))
    except Exception as ex:
        print(feed, "failed", ex)
try:
    e = json.loads(get("https://sylvan.apple.com/Aerials/2x/entries.json"))
    for g in e.get("assets", e if isinstance(e, list) else []):
        assets.append(g)
    print("2x", len(e.get("assets", [])))
except Exception as ex:
    print("2x failed", ex)

def key(u):  # the clip's name without path/scheme
    return (u or "").rsplit("/", 1)[-1]

by_h264 = {}
for a in assets:
    for k in ("url-1080-H264",):
        if a.get(k): by_h264.setdefault(key(a[k]), a)

ours = json.load(open("app/src/main/assets/aerials.json"))
out = []
for c in ours:
    a = by_h264.get(key(c["url"]))
    hevc = (a or {}).get("url-1080-SDR")
    row = {"url": c["url"], "place": c["place"], "hevc": None}
    if hevc and "HEVC" in hevc:
        st, size = head(hevc.replace("https://", "http://"))
        st2, size2 = head(c["url"])
        row.update(hevc=hevc.replace("https://", "http://"), status=st, size=size, h264_size=size2)
    out.append(row)
    print(row["place"], row.get("status"), row.get("size"), row.get("h264_size"), row["hevc"])
json.dump(out, open("aerials-scan.json", "w"), ensure_ascii=False, indent=1)
ok = [r for r in out if r.get("status") == 200]
print("with HEVC:", len(ok), "of", len(out), "| h264 GB", sum(r.get("h264_size", 0) for r in ok) / 1e9, "-> hevc GB", sum(r["size"] for r in ok) / 1e9)

print("SCAN-JSON " + json.dumps([{k: r.get(k) for k in ("url", "hevc", "status", "size", "h264_size")} for r in out], ensure_ascii=False))
