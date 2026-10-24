# Web2Apk Premium - Template
Developer: Nugroho Keren | Nama dev: Nugroho Y.R. Dev

Template versi PREMIUM: package name bebas (tanpa watermark), folder `www/` mendukung banyak file (hasil impor ZIP).
Website commit: `config/app.json`, `config/icon.png`, `www/*`, lalu menjalankan workflow **Build APK**.
Hasil: `app-debug.apk` di tab **Releases**.

## config/app.json

```json
{
  "appName": "Nama App",
  "packageName": "com.nama.app",
  "orientation": "auto",
  "permissions": ["camera", "location"]
}
```

### orientation
| Nilai | Arti |
|---|---|
| `portrait` | kunci potret |
| `landscape` | kunci lanskap |
| `auto` | otomatis (ikut sensor / pengaturan rotasi HP) |

### permissions (whitelist - hanya ini yang diterima)
| Kunci | Izin Android |
|---|---|
| `internet`, `network_state` | selalu aktif |
| `notification` | POST_NOTIFICATIONS |
| `bluetooth` | BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, BLUETOOTH_SCAN |
| `file_manager` | READ/WRITE_EXTERNAL_STORAGE (versi lama), READ_MEDIA_IMAGES/VIDEO/AUDIO |
| `camera` | CAMERA |
| `video` | CAMERA + RECORD_AUDIO |
| `audio` | RECORD_AUDIO, MODIFY_AUDIO_SETTINGS |
| `location` | ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION |
| `vibrate` | VIBRATE |
| `wifi_state` | ACCESS_WIFI_STATE |
| `wake_lock` | WAKE_LOCK (layar tetap menyala) |

Kunci di luar tabel diabaikan (tidak pernah masuk ke manifest). Izin berbahaya seperti
SMS, kontak, telepon, MANAGE_EXTERNAL_STORAGE, overlay, install paket, dan lokasi background
sengaja tidak tersedia.

Catatan: izin hanya "membuka pintu". Fitur web yang jalan di WebView: kamera/mikrofon
(`getUserMedia`), lokasi (`navigator.geolocation`), upload file, unduhan, getar.
Web Bluetooth dan Web Notification **belum didukung WebView Android** - butuh JavaScript bridge.
