# Spotify Auto Resume

Aplikasi Android eksperimental untuk mencoba melanjutkan pemutaran Spotify melalui Android MediaSession.

Independent project by AKONG. Not affiliated with Spotify.

## Features
- Standard Mode menggunakan Android MediaSession.
- Percobaan auto-resume ketika Spotify terdeteksi paused.
- Spotify OAuth dengan PKCE.
- Penyimpanan refresh token terenkripsi menggunakan Android Keystore.
- Dark UI.

## Requirements
- Android 6.0+
- Spotify terpasang.
- Notification Access untuk Standard Mode.

## Build
Jalankan:

./gradlew assembleDebug

APK tersedia di:
app/build/outputs/apk/debug/app-debug.apk

## Limitations
- Auto-resume bersifat best effort.
- Tidak menjamin playback 24/7.
- Pause manual juga dapat memicu auto-resume.
- Spotify API dapat membatasi akses berdasarkan persyaratan akun dan kebijakan platform.
- Aplikasi ini tidak memberikan Spotify Premium.

## Security
Jangan unggah release.keystore, release.properties, password, atau token pribadi ke GitHub.

Created by AKONG.
