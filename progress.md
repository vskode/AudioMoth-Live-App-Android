# Progress Log

Running log so work can resume in a new session. Read this together with `plan.md` (the overall plan).
Newest entries at the bottom. Update this file whenever a step is done or something new is learned.

---

## Current status (keep this section up to date)

- **Phase:** 0 (proof of concept). **384 kHz capture works on the Fairphone 6**: 10,000/10,000 full 768-byte packets, 0 errors, a valid 10.00 s WAV.
- Key-jingle test **passed**: real ultrasonic content was captured (see log).
- The user skipped the long-run test for now (still worth doing later). **Phase 0 is considered done.**
- **Phase 1 core done and working on the phone (2026-11-10):** live waveform + spectrogram, sample-rate selector 8–384 kHz (software resampling from 384 kHz), display width 1/5/10/20/60 s, pause/clear, touch readouts for frequency and time, colour map + low-amplitude scale menu, settings persisted. Verified at 48, 96 and 384 kHz.
- **Not yet ported:** night mode, Capture WAV, autosave, monitor/heterodyne, plot export, gain (HID), foreground service (streaming stops when the activity is destroyed), landscape-optimised layout, 'old firmware' warning, device-time-mismatch restart.
- **Next step candidates:** Capture WAV + autosave (reuse backstage wavFile.c/autosave.c), then Phase 3 items (gain via HID, heterodyne via AAudio, foreground service).
- Trigger a recording remotely: `adb shell am start -n info.openacousticdevices.audiomothlive/.MainActivity --ez record true`

---

## Environment (this Linux machine)

- Repo (fork): `~/Code/AudioMoth-Live-App-Android`, remote `github.com/vskode/AudioMoth-Live-App-Android`
- Android SDK: `~/Android/Sdk`. Installed: platform 36 and 37.0, build-tools 35/36, NDK `28.2.13676358`, CMake `3.22.1`, cmdline-tools `latest` (19.0)
- adb: `~/Android/Sdk/platform-tools/adb` (not on PATH)
- JDK: the system Java 21 is a JRE only (no javac). Use Android Studio's JBR (Java 25):
  `export JAVA_HOME=~/Programs/AndroidStudio/android-studio/jbr`
- Build: `cd android && ./gradlew assembleDebug` → `android/app/build/outputs/apk/debug/app-debug.apk`
- Versions: Gradle 9.2.1, AGP 8.13.0, Kotlin 2.3.0. (Gradle 8.x and Kotlin 2.2 fail on JDK 25 with `IllegalArgumentException: 25.0.4`.)
- Python: numpy available, no scipy or matplotlib. `android/tools/analyse_wav.py` needs only numpy.

## Phone / adb

- Phone: **Fairphone 6, Android 16 (API 36)**
- Phone IP (mDNS): `192.168.0.31`, connect port seen: `42781` (changes when wireless debugging restarts)
- Pairing: `adb pair 192.168.0.31:<pairing-port> <code>`. The first attempt used the wrong IP (172.16.0.2) and failed with "protocol fault".
- Find ports: `adb mdns services`
- Logs: `adb logcat -s AudioMothLive`

## Microphone

- Reports itself as `16d0:06f3`, manufacturer 'openacousticdevices.info', product '384kHz AudioMoth USB Microphone'.
- UAC1. Interface 0 = AudioControl; interface 1 = AudioStreaming (alt 1: iso IN ep 0x82, attr 0x0d async, wMaxPacketSize 768); interface 2 = HID (ep 0x01 OUT, 0x81 IN, 64 bytes, used for gain/config).
- Format: 1 ch, 16 bit, **a single discrete rate of 384000 Hz**, no sampling-frequency control on the endpoint.
- **Sample rate in software** means: the device always streams at 384 kHz, and the app decimates to the user-chosen rate (as `capture_data_callback` in backstage.c does). The final app needs a sample-rate selector (8–384 kHz) feeding the decimator. Nothing is sent to the device.

## Code so far (`android/`)

- `MainActivity.kt`: live view (port of index.js updateDisplay, frequencyDisplay.js, timeDisplay.js); layout mirrors index.html
- `UsbMicrophone.kt`: find/permission/claim/setInterface, starts Engine; handles attach/detach
- `Engine.kt` + `cpp/engine.c`: continuous usbfs streaming, backstage resampler, STFT; JNI getFrame/changeSampleRate/clear
- `Plotter.kt` + `cpp/plotter.c`: port of js/plotter.js into Android bitmaps
- `PlotView.kt` (bitmap + baseline + red selection + border), `Ui.kt` (Bootstrap colours, SegmentedControl = .pagination, buttons)
- `res/drawable/ic_play.xml`, `ic_pause.xml`: Bootstrap icons converted from assets/*.svg
- `PocActivity.kt`: the Phase 0 screen, now "USB diagnostics…" in the ⋮ menu (record 10 s WAV, descriptor dump)
- `.../UacDescriptors.kt`: UAC1 descriptor parser (AS interface, format type I, iso IN endpoint)
- `.../SpectrumCheck.kt`: on-device FFT band-energy summary
- `.../UsbCapture.kt` + `app/src/main/cpp/usb_capture.c`: raw usbfs isochronous capture (`USBDEVFS_SUBMITURB`/`REAPURB`, 16 URBs × 32 packets) → WAV. Chosen instead of libusb.
- `tools/analyse_wav.py`: desktop WAV check (bands, strongest HF component)
- `recordings/` (gitignored): WAVs pulled from the phone
- Nothing is committed yet. Untracked: `plan.md`, `progress.md`, `android/`, `.idea/`

---

## Log

### 2026-11-10
- Wrote `plan.md` (moved into the fork).
- Installed the NDK, CMake, platform 36 and cmdline-tools via sdkmanager.
- Created the Android project in `android/`; the debug APK builds (arm64-v8a + x86_64).
- Verified `analyse_wav.py` on a synthetic 120 kHz tone.
- The user thought the mic was a non-AudioMoth OAD USB mic, but its USB descriptors identify it as the "384kHz AudioMoth USB Microphone" firmware (16d0:06f3). Its sample rate is chosen in software.
- adb pairing attempt failed (wrong IP). Waiting for the user to re-pair with 192.168.0.31.
- Connected over adb (192.168.0.31:42781). The app installed and ran. Descriptors read: fixed 384 kHz, 16-bit mono, 768 B/packet. The user clarified that the sample rate is chosen in software, which is consistent with decimating from 384 kHz.
- Fixed: on Android 16 the buttons were hidden under the action bar (forced edge-to-edge). Now uses a NoActionBar theme plus window-insets padding.
- Added the `--ez record true` intent extra so recordings can be started over adb.
- **First successful 384 kHz recording** (`AM_384kHz_20261110_224348.wav`, ambient room): 10,000 packets, all 768 B, 0 errors. Band energy: 24–48k −7.6 dB, 48–96k −10.7 dB, 96–144k −17 dB, 144–192k −19 dB (re total). Phone and desktop analyses agree.
- **Key-jingle test** (`AM_384kHz_20261110_224557.wav`) vs the quiet recording, absolute band levels: 24–48k **+23.5 dB**, 48–96k **+10 dB**, 96–192k +2 dB. The 48–96k level tracks the jingling second by second (+5…+14 dB, including seconds with no clipping), so the ultrasound is genuine and not a clipping artefact. Some clipping (peak 32767) in s3–5: keys too close, gain could be lowered. 0 packet errors again.
- Note: the on-phone band report is relative to total energy, which is misleading when low frequencies dominate. Compare absolute levels instead (scratch script `compare.py`; worth folding into `tools/analyse_wav.py`).
- Started Phase 1 (sample-rate selection + spectrogram matching the desktop look).
- Phase 1 design: native `engine.c` (continuous usbfs streaming + exact port of the backstage resampler + unchanged `backstage/src/stft.c`, same 1<<25 ring buffer + STFT buffer layout), native `plotter.c` (line-by-line port of `js/plotter.js`, drawing into Android bitmaps; ARGB_8888 memory = 0xAABBGGRR like a canvas ImageData, so colour tables are identical). The Kotlin UI mimics the desktop layout. The PoC screen is kept as `PocActivity` (diagnostics).
- Desktop facts: STFT = non-overlapping 512-sample frames → 256 log2-magnitude bins (window sin(pi*i/(N-1))). The colour scale maps 2..15 (low-amp −3..12) to 256 colours. The spectrogram row→bin lookup skips the DC bin. Default sample rate 48 kHz; rates 8/16/32/48/96/192/250/384. Display widths 1/5/10/20/60 s (default 10 s). The resampler uses integer division `ceil(in/out)`, so 250 kHz is linear interpolation only (no averaging). Pause = freeze display (capture continues); Clear = reset count and shift start time.
- Written so far: `engine.c`, `plotter.c`, CMake (`engine` lib links backstage/src/stft.c via ../../../../../backstage). Both compile warning-free. Kotlin UI next.
- Phase 1 UI built and running on the phone. Fixes along the way: the Light theme needs APPEARANCE_LIGHT_STATUS_BARS for dark status icons; minSdk raised to 30 (WindowInsets.Type/insetsController).
- Observation: a steady ~51 kHz tone is visible in the room at 384 kHz (also seen as the 'strongest HF component 50.9 kHz' in the first Phase 0 recording). It's an environmental ultrasonic source, not an app artefact.
