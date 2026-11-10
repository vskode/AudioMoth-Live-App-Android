/****************************************************************************
 * android_device.c
 *
 * Android replacement for the miniaudio device layer of backstage.c
 * (checkForAudioMoth, startMicrophone, stopMicrophone).
 *
 * #included by generated/backstage_core.c, so it shares backstage's state.
 *
 *  - AudioMoth USB Microphone: the Android audio stack resamples USB audio,
 *    so the device is read directly with isochronous URBs on the usbfs file
 *    descriptor that Kotlin obtains from UsbManager (see UsbMicrophone.kt).
 *    Samples are fed to capture_data_callback(), exactly like miniaudio does
 *    on desktop.
 *  - Default input (phone microphone): miniaudio with the AAudio backend,
 *    identical configuration to the desktop default input.
 *
 * Kotlin announces the AudioMoth with backstage_setAudioMothDevice() and
 * withdraws it with backstage_clearAudioMothDevice(). The background thread
 * polls checkForAudioMoth() every 250 ms like the desktop device check, and
 * getFrame() then switches device, clamps the sample rate, etc.
 *****************************************************************************/

#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#define USB_NUMBER_OF_URBS                  16
#define USB_PACKETS_PER_URB                 8

/* Device announced by Kotlin */

static pthread_mutex_t androidDeviceMutex = PTHREAD_MUTEX_INITIALIZER;

static bool androidAudioMothAvailable = false;

static int androidUsbFd = -1;

static int androidUsbEndpoint;

static int androidUsbMaxPacketSize;

static int32_t androidUsbSampleRate = MAXIMUM_SAMPLE_RATE;

/* Active input */

static bool usbThreadRunning = false;

static bool defaultDeviceRunning = false;

static pthread_t usbThread;

static volatile bool usbStopRequested;

static struct usbdevfs_urb *usbUrbs[USB_NUMBER_OF_URBS];

static int usbActiveFd = -1;

static int usbActiveMaxPacketSize;

/* USB isochronous streaming */

static void usbResetUrb(struct usbdevfs_urb *urb) {

    urb->status = 0;
    urb->actual_length = 0;
    urb->error_count = 0;

    for (int i = 0; i < USB_PACKETS_PER_URB; i += 1) {
        urb->iso_frame_desc[i].length = usbActiveMaxPacketSize;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }

}

static void usbFreeUrbs(void) {

    for (int i = 0; i < USB_NUMBER_OF_URBS; i += 1) {
        if (usbUrbs[i]) { free(usbUrbs[i]->buffer); free(usbUrbs[i]); usbUrbs[i] = NULL; }
    }

}

static bool usbAllocateUrbs(int endpoint) {

    size_t size = sizeof(struct usbdevfs_urb) + USB_PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);

    for (int i = 0; i < USB_NUMBER_OF_URBS; i += 1) {

        usbUrbs[i] = calloc(1, size);
        if (usbUrbs[i] == NULL) { usbFreeUrbs(); return false; }

        usbUrbs[i]->buffer = malloc(USB_PACKETS_PER_URB * usbActiveMaxPacketSize);
        if (usbUrbs[i]->buffer == NULL) { free(usbUrbs[i]); usbUrbs[i] = NULL; usbFreeUrbs(); return false; }

        usbUrbs[i]->type = USBDEVFS_URB_TYPE_ISO;
        usbUrbs[i]->endpoint = (unsigned char)endpoint;
        usbUrbs[i]->flags = USBDEVFS_URB_ISO_ASAP;
        usbUrbs[i]->buffer_length = USB_PACKETS_PER_URB * usbActiveMaxPacketSize;
        usbUrbs[i]->number_of_packets = USB_PACKETS_PER_URB;

    }

    return true;

}

static void *usbThreadBody(void *ptr) {

    (void)ptr;

    int submitted = 0;

    int16_t *samples = malloc(USB_PACKETS_PER_URB * usbActiveMaxPacketSize);

    for (int i = 0; i < USB_NUMBER_OF_URBS && samples != NULL; i += 1) {

        usbResetUrb(usbUrbs[i]);

        if (ioctl(usbActiveFd, USBDEVFS_SUBMITURB, usbUrbs[i]) < 0) {
            printf("[USB] SUBMITURB failed: %s", strerror(errno));
            usbStopRequested = true;
            break;
        }

        submitted += 1;

    }

    while (!usbStopRequested) {

        struct usbdevfs_urb *urb = NULL;

        if (ioctl(usbActiveFd, USBDEVFS_REAPURB, &urb) < 0) {
            if (errno == EINTR) continue;
            printf("[USB] REAPURB failed: %s (device unplugged?)", strerror(errno));
            submitted = 0;
            break;
        }

        submitted -= 1;

        /* Gather the packet payloads into one block and pass to backstage */

        uint8_t *base = (uint8_t*)urb->buffer;

        uint32_t bytes = 0;

        for (int i = 0; i < urb->number_of_packets; i += 1) {

            struct usbdevfs_iso_packet_desc *pd = &urb->iso_frame_desc[i];

            if (pd->status != 0 || pd->actual_length == 0) continue;

            uint32_t length = pd->actual_length & ~1u;

            memcpy((uint8_t*)samples + bytes, base + (size_t)i * usbActiveMaxPacketSize, length);

            bytes += length;

        }

        if (bytes > 0) capture_data_callback(NULL, NULL, samples, bytes / NUMBER_OF_BYTES_IN_SAMPLE);

        if (usbStopRequested) break;

        usbResetUrb(urb);

        if (ioctl(usbActiveFd, USBDEVFS_SUBMITURB, urb) < 0) {
            printf("[USB] re-SUBMITURB failed: %s", strerror(errno));
            break;
        }

        submitted += 1;

    }

    /* Cancel and reap outstanding transfers */

    for (int i = 0; i < USB_NUMBER_OF_URBS; i += 1) if (usbUrbs[i]) ioctl(usbActiveFd, USBDEVFS_DISCARDURB, usbUrbs[i]);

    while (submitted > 0) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(usbActiveFd, USBDEVFS_REAPURB, &urb) < 0) break;
        submitted -= 1;
    }

    free(samples);

    /* Same effect as the miniaudio 'stopped' notification on desktop */

    pthread_mutex_lock(&stopStartMutex);

    stopped = true;

    pthread_mutex_unlock(&stopStartMutex);

    puts("[USB] Capture thread finished");

    return NULL;

}

static bool usbStart(void) {

    pthread_mutex_lock(&androidDeviceMutex);

    bool available = androidAudioMothAvailable;

    usbActiveFd = androidUsbFd;

    usbActiveMaxPacketSize = androidUsbMaxPacketSize;

    int endpoint = androidUsbEndpoint;

    pthread_mutex_unlock(&androidDeviceMutex);

    if (!available || usbActiveFd < 0) return false;

    if (!usbAllocateUrbs(endpoint)) return false;

    usbStopRequested = false;

    if (pthread_create(&usbThread, NULL, usbThreadBody, NULL) != 0) {
        usbFreeUrbs();
        return false;
    }

    usbThreadRunning = true;

    return true;

}

static void usbStop(void) {

    if (!usbThreadRunning) return;

    usbStopRequested = true;

    /* Discarding wakes a REAPURB blocked in the capture thread */

    for (int i = 0; i < USB_NUMBER_OF_URBS; i += 1) if (usbUrbs[i]) ioctl(usbActiveFd, USBDEVFS_DISCARDURB, usbUrbs[i]);

    pthread_join(usbThread, NULL);

    usbFreeUrbs();

    usbThreadRunning = false;

    usbActiveFd = -1;

}

/* Replacements for the desktop functions */

static device_check_t checkForAudioMoth(ma_context *context, bool updateAudioMothSettings) {

    (void)context;

    pthread_mutex_lock(&androidDeviceMutex);

    bool found = androidAudioMothAvailable;

    int32_t sampleRate = androidUsbSampleRate;

    pthread_mutex_unlock(&androidDeviceMutex);

    if (found && updateAudioMothSettings) audioMothSampleRate = sampleRate;

    device_check_t device_check = {
        .audioMothFound = found,
        .oldAudioMothFound = false
    };

    return device_check;

}

static bool startMicrophone(ma_context *context, bool usingAudioMoth) {

    /* Identical to desktop startMicrophone() */

    inputDeviceSampleRate = usingAudioMoth ? audioMothSampleRate : maximumDefaultSampleRate;

    sprintf(inputDeviceName, usingAudioMoth ? "%dkHz AudioMoth USB Microphone" : "%dkHz Default Input", inputDeviceSampleRate / HERTZ_IN_KILOHERTZ);

    sprintf(inputDeviceCommentName, usingAudioMoth ? "a %dkHz AudioMoth USB Microphone" : "the %dkHz default input", inputDeviceSampleRate / HERTZ_IN_KILOHERTZ);

    currentSampleRate = MIN(requestedSampleRate, inputDeviceSampleRate);

    requestedSampleRate = currentSampleRate;

    if (usingAudioMoth) return usbStart();

    /* Default input: phone microphone via miniaudio (AAudio) */

    ma_device_config captureDeviceConfig = ma_device_config_init(ma_device_type_capture);

    captureDeviceConfig.capture.pDeviceID = NULL;
    captureDeviceConfig.capture.format = ma_format_s16;
    captureDeviceConfig.capture.channels = 1;
    captureDeviceConfig.capture.shareMode  = ma_share_mode_shared;

    captureDeviceConfig.sampleRate = inputDeviceSampleRate;
    captureDeviceConfig.periodSizeInFrames = inputDeviceSampleRate / CALLBACKS_PER_SECOND;
    captureDeviceConfig.dataCallback = capture_data_callback;
    captureDeviceConfig.notificationCallback = capture_notification_callback;

    /* Ask for the least processed signal (no AGC / noise suppression) */
    captureDeviceConfig.aaudio.inputPreset = ma_aaudio_input_preset_unprocessed;

    ma_result result = ma_device_init(context, &captureDeviceConfig, &captureDevice);

    if (result != MA_SUCCESS) {
        printf("[DEVICE] Default input init failed (%d) - microphone permission?", result);
        return false;
    }

    result = ma_device_start(&captureDevice);

    if (result != MA_SUCCESS) {
        ma_device_uninit(&captureDevice);
        memset(&captureDevice, 0, sizeof(ma_device));
        return false;
    }

    defaultDeviceRunning = true;

    return true;

}

static void stopMicrophone(void) {

    usbStop();

    if (defaultDeviceRunning) {

        ma_device_stop(&captureDevice);

        ma_device_uninit(&captureDevice);

        memset(&captureDevice, 0, sizeof(ma_device));

        defaultDeviceRunning = false;

    }

    pthread_mutex_lock(&stopStartMutex);

    stopped = true;

    pthread_mutex_unlock(&stopStartMutex);

}

/* Called from JNI (Kotlin UI thread) */

static void backstage_setAudioMothDevice(int fd, int endpoint, int maxPacketSize, int32_t sampleRate) {

    pthread_mutex_lock(&androidDeviceMutex);

    androidUsbFd = fd;
    androidUsbEndpoint = endpoint;
    androidUsbMaxPacketSize = maxPacketSize;
    androidUsbSampleRate = sampleRate;
    androidAudioMothAvailable = true;

    pthread_mutex_unlock(&androidDeviceMutex);

}

/* After this returns the file descriptor is no longer used and may be closed */

static void backstage_clearAudioMothDevice(void) {

    pthread_mutex_lock(&androidDeviceMutex);

    androidAudioMothAvailable = false;

    androidUsbFd = -1;

    pthread_mutex_unlock(&androidDeviceMutex);

    usbStop();

}
