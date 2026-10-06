# GNSS Inspector

An Android app that shows the satellites your phone is listening to, and everything its GNSS chip learns from them, explained in plain language.

## Screens

| Tab | What you see |
|---|---|
| **Overview** | Your coordinates, live status, satellites in use, an accuracy meter, and each satellite system (GPS, Galileo, BeiDou, GLONASS, QZSS, NavIC, SBAS) |
| **Sky** | A radar-style sky plot: every satellite glows in its system's colour, filled when used for your position |
| **Satellites** | Each satellite by name, with direction, height in the sky, bands and signal bars. Tap one for its own page: live height and signal charts, orbit type, raw measurement, navigation message and NMEA report |
| **Advanced** | Full detail for experts: position fields and DOP, raw measurements (pseudorange, Doppler, carrier phase, receiver clock), decoded GPS navigation messages, live NMEA, chip capabilities |

Every ⓘ explains the term next to it. A short "How it works" explainer covers how satellite positioning works.

## What Android exposes

The app shows every layer Android makes available: `Location`, `GnssStatus`, `GnssMeasurementsEvent` (raw measurements), `GnssNavigationMessage`, NMEA, `GnssCapabilities` and `GnssAntennaInfo`. Support varies by phone. Raw measurements are mandatory from Android 10, but navigation messages are optional and many phones don't provide them. The app says so instead of showing empty screens.

For GPS L1 C/A navigation messages, the app decodes clock, health, ephemeris, almanac, ionosphere and UTC data, then computes where the satellite is right now from its broadcast orbit.

The raw radio signal itself never leaves the GNSS chip on any phone.

## Build & install

Requires JDK 17 and the Android SDK (API 36).

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew testDebugUnitTest` runs the navigation-message decoder tests.

## Credits

Fonts: [IBM Plex Sans and IBM Plex Mono](https://github.com/IBM/plex), SIL Open Font License 1.1 (licences in `app/src/main/assets/licenses`).
