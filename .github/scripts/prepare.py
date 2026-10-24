"""Menyiapkan project Android dari config yang di-commit oleh website."""
import json, os, re, shutil, subprocess, sys
from xml.sax.saxutils import escape

JAVA_KW = {"abstract","assert","boolean","break","byte","case","catch","char","class","const",
    "continue","default","do","double","else","enum","extends","final","finally","float","for",
    "goto","if","implements","import","instanceof","int","interface","long","native","new",
    "package","private","protected","public","return","short","static","strictfp","super",
    "switch","synchronized","this","throw","throws","transient","try","void","volatile","while"}

# ---------------------------------------------------------------------------
# ORIENTASI  (nilai kiri = yang boleh ditulis di app.json, kanan = nilai Android)
# ---------------------------------------------------------------------------
ORIENTATIONS = {
    "portrait": "portrait", "potrait": "portrait",
    "kunci_portrait": "portrait", "kunci_potrait": "portrait",
    "landscape": "landscape", "kunci_landscape": "landscape",
    "auto": "unspecified", "otomatis": "unspecified",
    "otomatis_auto": "unspecified", "unspecified": "unspecified",
}

# ---------------------------------------------------------------------------
# IZIN - WHITELIST. Hanya izin di tabel ini yang pernah masuk ke manifest.
# Nama izin mentah dari config TIDAK PERNAH dipakai langsung (anti injeksi).
# Sengaja TIDAK ADA: SMS, kontak, call log, telepon, MANAGE_EXTERNAL_STORAGE,
# SYSTEM_ALERT_WINDOW, REQUEST_INSTALL_PACKAGES, QUERY_ALL_PACKAGES,
# lokasi background, accessibility, device admin, dsb.
# ---------------------------------------------------------------------------
def P(name, **attrs):
    return (name, attrs)

PERMISSIONS = {
    "internet":      [P("INTERNET")],
    "network_state": [P("ACCESS_NETWORK_STATE")],
    "notification":  [P("POST_NOTIFICATIONS")],
    "bluetooth":     [P("BLUETOOTH", maxSdkVersion="30"),
                      P("BLUETOOTH_ADMIN", maxSdkVersion="30"),
                      P("BLUETOOTH_CONNECT"),
                      P("BLUETOOTH_SCAN", usesPermissionFlags="neverForLocation")],
    "file_manager":  [P("READ_EXTERNAL_STORAGE", maxSdkVersion="32"),
                      P("WRITE_EXTERNAL_STORAGE", maxSdkVersion="28"),
                      P("READ_MEDIA_IMAGES"), P("READ_MEDIA_VIDEO"), P("READ_MEDIA_AUDIO")],
    "camera":        [P("CAMERA")],
    "video":         [P("CAMERA"), P("RECORD_AUDIO")],          # video / perekaman
    "audio":         [P("RECORD_AUDIO"), P("MODIFY_AUDIO_SETTINGS")],
    "location":      [P("ACCESS_COARSE_LOCATION"), P("ACCESS_FINE_LOCATION")],
    # izin tambahan yang aman (normal-level / tanpa data pribadi)
    "vibrate":       [P("VIBRATE")],
    "wifi_state":    [P("ACCESS_WIFI_STATE")],
    "wake_lock":     [P("WAKE_LOCK")],                          # layar tetap menyala
}

# <uses-feature required="false" -> app tetap bisa dipasang di HP tanpa hardware itu
FEATURES = {
    "bluetooth": ["android.hardware.bluetooth", "android.hardware.bluetooth_le"],
    "camera":    ["android.hardware.camera", "android.hardware.camera.autofocus"],
    "video":     ["android.hardware.camera", "android.hardware.microphone"],
    "audio":     ["android.hardware.microphone"],
    "location":  ["android.hardware.location", "android.hardware.location.gps"],
}

# nama lain yang diterima -> kunci resmi
ALIASES = {
    "notifikasi": "notification", "notifications": "notification",
    "kamera": "camera",
    "perekaman": "video", "video_perekaman": "video", "video/perekaman": "video",
    "recording": "video",
    "lokasi": "location", "gps": "location",
    "file": "file_manager", "files": "file_manager", "storage": "file_manager",
    "file_manager": "file_manager", "filemanager": "file_manager",
    "access_network_state": "network_state", "jaringan": "network_state",
    "mikrofon": "audio", "microphone": "audio",
    "getar": "vibrate", "vibration": "vibrate",
    "wifi": "wifi_state", "keep_screen_on": "wake_lock",
}

def norm(s):
    return re.sub(r"[\s\-]+", "_", str(s).strip().lower())

with open("config/app.json", encoding="utf-8") as f:
    cfg = json.load(f)

name = str(cfg.get("appName", "Web App")).strip()[:50] or "Web App"
pkg = str(cfg.get("packageName", "")).strip()

if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", pkg) or any(p in JAVA_KW for p in pkg.split(".")):
    sys.exit("Package name tidak valid: %r (contoh benar: com.nama.app)" % pkg)

# --- orientasi
ori_key = norm(cfg.get("orientation", "auto"))
if ori_key not in ORIENTATIONS:
    sys.exit("Orientasi tidak valid: %r (pilih: portrait / landscape / auto)" % cfg.get("orientation"))
orientation = ORIENTATIONS[ori_key]

# --- izin
raw_perms = cfg.get("permissions", [])
if not isinstance(raw_perms, list):
    sys.exit('"permissions" harus berupa daftar, contoh: ["camera", "location"]')
wanted = ["internet", "network_state"]          # selalu aktif
for item in raw_perms:
    key = norm(item)
    key = ALIASES.get(key, key)
    if key in PERMISSIONS:
        if key not in wanted:
            wanted.append(key)
    else:
        print("PERINGATAN: izin %r tidak ada di whitelist -> diabaikan" % item)

perm_lines, seen = [], set()
for key in wanted:
    for pname, attrs in PERMISSIONS[key]:
        if pname in seen:
            continue
        seen.add(pname)
        extra = "".join(' android:%s="%s"' % (k, v) for k, v in attrs.items())
        perm_lines.append('    <uses-permission android:name="android.permission.%s"%s />' % (pname, extra))
feat_seen = set()
for key in wanted:
    for feat in FEATURES.get(key, []):
        if feat in feat_seen:
            continue
        feat_seen.add(feat)
        perm_lines.append('    <uses-feature android:name="%s" android:required="false" />' % feat)

# 0) Manifest: blok izin + orientasi
mpath = "app/src/main/AndroidManifest.xml"
manifest = open(mpath, encoding="utf-8").read()
START, END = "<!-- PERMISSIONS:START -->", "<!-- PERMISSIONS:END -->"
pat = re.compile(re.escape(START) + r".*?" + re.escape(END), re.S)
if not pat.search(manifest):
    sys.exit("Penanda PERMISSIONS:START/END tidak ditemukan di AndroidManifest.xml")
block = START + "\n" + "\n".join(perm_lines) + "\n    " + END
manifest = pat.sub(lambda _m: block, manifest, count=1)
manifest, n = re.subn(r'android:screenOrientation="[^"]*"',
                      'android:screenOrientation="%s"' % orientation, manifest, count=1)
if n != 1:
    sys.exit("android:screenOrientation tidak ditemukan di AndroidManifest.xml")
open(mpath, "w", encoding="utf-8").write(manifest)

# 1) Nama app -> strings.xml (di-escape)
safe = escape(name).replace("'", "\\'").replace('"', '\\"')
if safe.startswith("@") or safe.startswith("?"):
    safe = "\\" + safe
with open("app/src/main/res/values/strings.xml", "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <string name="app_name">%s</string>\n</resources>\n' % safe)

# 2) Package name -> applicationId
path = "app/build.gradle"
text = open(path, encoding="utf-8").read()
text = re.sub(r'applicationId\s+".*?"', 'applicationId "%s"' % pkg, text, count=1)
open(path, "w", encoding="utf-8").write(text)

# 3) Salin www/ ke assets
for need in ("www/index.html", "www/url.txt"):
    if not os.path.exists(need):
        sys.exit("File wajib tidak ada: " + need)
shutil.copytree("www", "app/src/main/assets/www", dirs_exist_ok=True)

# 4) Icon -> semua ukuran mipmap
icon = "config/icon.png"
if not os.path.exists(icon):
    sys.exit("config/icon.png tidak ada")
im = shutil.which("magick") or shutil.which("convert")
if not im:
    sys.exit("ImageMagick tidak ditemukan")
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dpi, px in sizes.items():
    d = "app/src/main/res/mipmap-%s" % dpi
    os.makedirs(d, exist_ok=True)
    subprocess.check_call([im, icon, "-resize", "%dx%d^" % (px, px), "-gravity", "center",
                           "-extent", "%dx%d" % (px, px), "PNG32:%s/ic_launcher.png" % d])

print("OK -> app: %s | package: %s | orientasi: %s | izin: %s"
      % (name, pkg, orientation, ", ".join(wanted)))
