# Precip Log

Free rain and snow tracker for your exact location, using [Open-Meteo](https://open-meteo.com) data.

- **Web app:** https://cwk021t.github.io/precip/
- **Android app:** download `PrecipLog.apk` from the latest [Release](https://github.com/cwk021t/precip/releases/latest)

`index.html` is the whole interface. GitHub Pages serves it, and the Android app (in `android/`) bundles the same file and adds background alerts. Every push to `main` builds a new signed APK through GitHub Actions.
