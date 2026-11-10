# AudioMoth Live: Android (Phase 0 proof of concept)

Records 10 s from an AudioMoth USB Microphone at its native sample rate (up to 384 kHz),
bypassing the Android audio stack. See `../plan.md`.

How it works:
- `MainActivity.kt` finds the device (16d0:06f3), gets USB permission, parses the UAC1
  descriptors (`UacDescriptors.kt`), claims the AudioStreaming interface (detaching the
  kernel driver) and selects the streaming alternate setting.
- `cpp/usb_capture.c` submits isochronous URBs directly to the usbfs file descriptor and
  writes a WAV file.
- `SpectrumCheck.kt` prints energy per frequency band so the result is visible on the phone.

## Build

Android Studio: open this `android/` folder. Or from the command line:

```
export JAVA_HOME=~/Programs/AndroidStudio/android-studio/jbr
./gradlew assembleDebug
```

Requires SDK platform 36, NDK 28.2.13676358 and CMake 3.22.1.

## Install and debug over Wi-Fi

The phone's USB port is used by the AudioMoth, so use wireless debugging (Android 11+):

```
adb pair <ip>:<pairing-port>      # Developer options -> Wireless debugging -> Pair device with pairing code
adb connect <ip>:<port>           # IP and port shown on the Wireless debugging screen
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s AudioMothLive
```

## Get the recording

```
adb pull /storage/emulated/0/Android/data/info.openacousticdevices.audiomothlive/files/ recordings/
python3 tools/analyse_wav.py recordings/files/*.wav
```
