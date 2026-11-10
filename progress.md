# Progress Log

Running log so work can resume in a new session. Read this together with `plan.md` (the overall plan).
Newest entries at the bottom. Update this file whenever a step is done or something new is learned.

---

## Current status (keep this section up to date)

**As of the end of 2026-11-10 (session closed, documented):** the Android app has feature parity with most of the desktop app. Read `android/README.md` for architecture/build/usage. Remaining items are listed at the bottom ("Next session").


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

### 2026-11-10 (cont.): Phase 1b, porting backstage.c
- The user committed Phase 0/1 themselves and removed plan.md. **Rule: never stash/commit/push** (now in ~/.claude/CLAUDE.md).
- The user requested: desktop-like device switching. Unplug → keep recording from the default input (phone mic) at 48 kHz, rates above 48 greyed out. Re-plug → switch to the AudioMoth immediately, stay at 48 kHz, but ultrasonic rates become enabled. Plus the rest of the missing features (Capture WAV, autosave, monitor/heterodyne, night mode, ...).
- Decision: replace my own engine.c with a **port of backstage.c itself**. `android/tools/port_backstage.py` generates `cpp/generated/backstage_core.c` from desktop backstage.c (everything before "Exported functions"), with checked patches: Node-API removed (results polled), device layer → `cpp/android_device.c` (usbfs for the AudioMoth, miniaudio/AAudio default input = phone mic, unprocessed preset), logging → logcat (`android_logging.h`). `cpp/backstage_jni.c` re-implements every exported function (getFrame with all its device-switch/restart logic, capture, setPause, autosave, monitor, simulation, local time).
- Kotlin announces the AudioMoth via nativeSetAudioMothDevice(fd, ep, mps, rate) and withdraws it via nativeClearAudioMothDevice() (stops the USB thread, so the fd can be closed safely). The background thread polls it every 250 ms like the desktop device check.
- Native compiles warning-free. Next: Kotlin Backstage.kt + UI (pause = green/disables controls, Capture WAV, autosave, monitor/heterodyne, local time, simulation menu).
- Files are saved to /storage/emulated/0/Recordings/AudioMoth Live (direct file paths work for app-owned files under scoped storage; MediaScanner after writes).
- The user offered to compare levels with the desktop app using a wood chime. Planned for after this round.
- Kotlin side done: `Backstage.kt` (replaces Engine.kt), MainActivity = port of index.js (pause → green + disables controls, Capture WAV, autosave Off/1/5/10/60, monitor Off/Monitor/Heterodyne with feedback warning + hold-repeat ±100 Hz + double-tap + white line, menu: Local Time, Night Mode (rebuilds UI with uiNight.css colours), Simulate (BAT.WAV packaged from ../../simulator as an asset), Colour, Low-amp, File Destination info, USB diagnostics). RECORD_AUDIO permission for the default input.
- Tested on the phone: starts on the AudioMoth at 384 kHz via the ported engine. **Capture WAV works**: `20261110_232732_682.WAV`, header comment identical to desktop ("Recorded at 23:27:32.682 10/11/2026 (UTC+1) by AudioMoth Live using a 384kHz AudioMoth USB Microphone.").
- Pending tests: unplug/replug (needs the user), monitor/heterodyne audio, autosave, night mode, simulation.
- **User tested everything: all good from a UI/UX perspective.** Unplug → `re-SUBMITURB failed: No such device` → detached → default device within ~0.1 s; re-plug → AudioMoth within ~2 s at 48 kHz with ultrasonic rates re-enabled. Autosave start/stop/files, Capture WAV (5 s and 60 s), pause, all rates, heterodyne hold-repeat, simulation and local time all exercised OK.
- Observed: two 'Restarting due to time mismatch' events at 23:35:32 and 23:36:27 (while paused, around when the phone went to sleep/adb went offline). This is the desktop's recovery path, but check whether the USB stream stalls when the screen turns off (→ foreground service / wake lock).
- The user recorded a wind chime with the AudioMoth and the phone mic at 48 kHz: files ending `_361` and `_123` in Recordings/AudioMoth Live. Compare their frequencies and find the 'hidden message' in one of them (TODO; adb went offline before pulling).
- **Next feature requested:** the user chooses the file destination (desktop 'Set File Destination').
- Implemented **Set File Destination** (⋮ menu): system folder picker (ACTION_OPEN_DOCUMENT_TREE) → tree id mapped to a path (primary: → /storage/emulated/0/..., XXXX-XXXX: → /storage/XXXX-XXXX/...) → test-write a .WAV. If scoped storage blocks the folder, offer 'All files access' (MANAGE_EXTERNAL_STORAGE, declared). The choice is saved in prefs ('fileDestination'); 'Default' button = Recordings/AudioMoth Live. Builds; **not yet installed/tested** (adb offline: phone asleep / wireless debugging dropped).
- Reconnected (adb port now 192.168.0.31:45079; `adb mdns services` finds it). Installed the file-destination build; it starts fine. **The user still needs to test** ⋮ > Set File Destination.
- **Wind chime comparison** (both at 48 kHz): `_123` = AudioMoth, `_361` = phone 'default input'. Chime partials match: 1759.1/1759.1 Hz, 4735.7 vs 4736.0, 4782.6 vs 4782.9, 9005.3 vs 9005.5, 9046.3 vs 9047.7 Hz (≈63 ppm clock difference, normal). The AudioMoth is ~23 dB higher in RMS (4956 vs 369) and +27 dB on the 1759 Hz partial. Both recordings clip on strikes, so use lower gain for level comparisons with the desktop app.
- Hidden message: speech at 0.5–3 s of `_361` (phone mic only). Offline Vosk (scratchpad venv) English → nothing; German small model → 'hallo wird' (consistent). Probably 'Hallo Welt'? Ask the user to confirm.
- Scratch tools: scratchpad chime.py (peak finder), specpng.py (numpy-only spectrogram PNG), transcribe.py + vosk venv.
- The user tested Set File Destination: works (created a folder in Documents → files saved there).
- **Background running** implemented: `RecordingService.kt` (foreground service type microphone|connectedDevice = 0x90, partial wake lock, ongoing notification with device name + Quit action; restarted after the mic permission is granted to add the microphone type), POST_NOTIFICATIONS requested with RECORD_AUDIO, and a 100 ms background tick on the main looper that keeps calling backstage getFrame() (device switching/restarts, capture/autosave events, notification text) while the activity isn't visible. On resume, a pending device refresh updates the UI.
- **Verified:** with the screen off (KEYCODE_SLEEP) for ~3.5 min at 384 kHz with 1-minute autosave: files 23:47:37 (partial), 23:48:00, 23:49:00, 23:50:00, each full minute exactly 46,080,488 bytes (60 s × 384 kHz × 2 + 488 header), no time-mismatch restarts, adb stayed connected.
- Documentation: `android/README.md` rewritten (features, architecture diagram, file table, build, Wi-Fi adb, usage, notes); short Android section added to the root README.
- Note: autosave was left ON (1 min, 384 kHz, Documents/Ttt) at the end of the test. The user should switch it off (≈2.7 GB/h).

## Next session (candidates)
1. AudioMoth gain controls via USB HID (interface 2, ep 0x01/0x81, 64-byte reports). Port the protocol from the desktop `audiomoth-usb-microphone-cmd` / AudioMoth-USB-Microphone-App.
2. Wind chime level comparison against the desktop Electron app (same gain, softer strikes, no clipping).
3. Plot export (PNG/JPG/PDF), landscape layout, 'Enable High Sample Rate For Default Input' (probably unnecessary on phones).
4. ~~Hidden message~~ solved: it was 'Hello Claude' (Vosk German heard 'hallo wird'; my guess 'Hallo Welt' was close).
