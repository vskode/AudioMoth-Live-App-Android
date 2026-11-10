# AudioMoth Live: Android

Android version of the AudioMoth Live App. It records and displays live audio from the
**AudioMoth USB Microphone** at its full sample rate (up to **384 kHz**) on an Android phone,
and falls back to the phone's own microphone when no AudioMoth is plugged in, as the desktop
app does with the default input.

Tested on a Fairphone 6 (Android 16) with the 384 kHz AudioMoth USB Microphone firmware.

## Features

Same behaviour as the desktop app:

- Live waveform and spectrogram (same STFT, colour maps and scaling as the desktop).
- Sample rate 8 / 16 / 32 / 48 / 96 / 192 / 250 / 384 kHz. The AudioMoth always streams at
  its native rate; lower rates are produced in software by the desktop resampler.
- Plug and unplug while running. Unplugging switches to "48kHz Default Input" (phone
  microphone) and greys out the ultrasonic rates. Plugging back in switches to the AudioMoth,
  staying at the current rate, and re-enables the ultrasonic rates.
- Display width 1 / 5 / 10 / 20 / 60 s, pause/play, clear.
- Capture WAV (last display width), and autosave in 1 / 5 / 10 / 60 minute files. File names,
  WAV headers and comments are identical to the desktop app.
- Monitor and heterodyne output (hold the ▼▲ buttons to repeat, double-tap the spectrogram to
  set the frequency), with the desktop's audio feedback warning.
- Touch readouts: tap/drag the spectrogram for frequency, the waveform for time.
- ⋮ menu: Local Time, Night Mode, Simulate (the desktop's bat example), Colour map, Low-Amplitude
  Colour Scale, Set File Destination, USB Diagnostics.
- Keeps recording with the screen off or the app in the background (foreground service and
  wake lock, with an ongoing notification and a Quit action).

Not ported yet: AudioMoth gain controls (USB HID), plot image export (PNG/JPG/PDF),
"Enable High Sample Rate For Default Input", landscape-specific layout, update checker.

## How it works

The Android audio stack resamples USB microphones (usually to 48 kHz), so the app bypasses it:

```
AudioMoth ──USB isochronous──▶ android_device.c (usbfs URBs on the fd from UsbManager)
Phone mic ──AAudio (miniaudio)──▶            │
                                             ▼
                generated/backstage_core.c  (desktop backstage.c: resampler, ring buffer,
                                             STFT, capture, autosave, playback/heterodyne,
                                             simulator, background device check)
                                             │ JNI (backstage_jni.c)
                                             ▼
               Kotlin UI (MainActivity = port of index.js) + plotter.c (port of plotter.js)
```

| File | Role |
|---|---|
| `tools/port_backstage.py` | Generates `cpp/generated/backstage_core.c` from the desktop `backstage/src/backstage.c` with a few checked patches. **Re-run it after updating backstage.c from upstream.** |
| `app/src/main/cpp/android_device.c` | Replaces the desktop device layer. Reads the AudioMoth via usbfs isochronous transfers and opens the phone mic via miniaudio/AAudio (unprocessed preset). |
| `app/src/main/cpp/backstage_jni.c` | JNI versions of the desktop's exported N-API functions (`getFrame`, `capture`, `setPause`, `setAutoSave`, `setMonitor`, ...). |
| `app/src/main/cpp/plotter.c` | Line-by-line port of `js/plotter.js`. Android bitmaps have the same byte order as canvas ImageData, so the colour tables are identical. |
| `Backstage.kt`, `Plotter.kt` | Kotlin JNI wrappers. |
| `MainActivity.kt` | UI: port of `index.html` / `js/index.js` / `frequencyDisplay.js` / `timeDisplay.js`. |
| `UsbMicrophone.kt` | Finds the AudioMoth (16d0:06f3), gets USB permission, claims the AudioStreaming interface and announces it to the engine. |
| `UacDescriptors.kt` | USB Audio Class 1.0 descriptor parser. |
| `RecordingService.kt` | Foreground service (microphone + connectedDevice) and partial wake lock for background recording. |
| `PlotView.kt`, `Ui.kt` | Plot view (bitmap, baseline, selection, heterodyne line) and Bootstrap-style widgets/colours incl. night mode. |
| `PocActivity.kt`, `cpp/usb_capture.c` | The original proof of concept, available as ⋮ › USB Diagnostics (descriptor dump and 10 s raw test recording). |
| `tools/analyse_wav.py` | Desktop helper: WAV duration and energy per frequency band. |

The shared C sources (`stft.c`, `wavFile.c`, `autosave.c`, `heterodyne.c`, `biquad.c`,
`simulator.c`, `xtime.c`, `miniaudio.h`) are compiled straight from `../backstage`, unchanged.
The simulator example `../simulator/BAT.WAV` is packaged as an asset.

## Build

Requirements: Android SDK platform 36, NDK `28.2.13676358`, CMake `3.22.1`, and a JDK that
includes a compiler (Android Studio's bundled JBR works). The Gradle wrapper (9.2.1) does the rest.

```
export JAVA_HOME=~/Programs/AndroidStudio/android-studio/jbr   # or your JDK
cd android
./gradlew assembleDebug
```

Or open the `android/` folder in Android Studio and press Run.

## Install and debug over Wi-Fi

The phone's USB port is used by the AudioMoth (through a USB-C OTG adapter), so use wireless
debugging (Settings › Developer options › Wireless debugging):

```
adb pair <phone-ip>:<pairing-port> <code>     # "Pair device with pairing code"
adb mdns services                             # shows the current connect port
adb connect <phone-ip>:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s AudioMothLive                   # backstage logs ([BACKSTAGE], [AUTOSAVE], [USB], ...)
```

## Using it

1. Plug the AudioMoth USB Microphone into the phone with an OTG adapter and open AudioMoth Live.
   Allow USB access (tick "always open" to skip this next time) and microphone access (used
   for the phone-mic fallback).
2. Files are saved to `Recordings/AudioMoth Live` by default. Change this with
   ⋮ › Set File Destination. Folders outside Recordings/Music/Documents/Download need
   "All files access", which the app asks for when needed.
3. Recording continues with the screen off. Use Quit in the notification, or swipe the app
   away, to stop it. Autosave finishes the current file on exit.

## Notes

- 384 kHz, 16-bit mono WAV is about 2.7 GB per hour.
- The app keeps the screen on while it's in front, like a measuring instrument.
- Verified: 10,000/10,000 USB packets with no errors at 384 kHz. Chime partials recorded with
  the AudioMoth and the phone mic match to within about 60 ppm.
