/****************************************************************************
 * engine.c
 *
 * Android replacement for the capture part of backstage.c:
 *
 *  - streams isochronous USB audio from the AudioMoth USB Microphone via
 *    usbfs (see usb_capture.c for the Phase 0 proof of concept),
 *  - downsamples to the selected sample rate with the same resampler as
 *    capture_data_callback() in backstage/src/backstage.c,
 *  - fills the same ring buffer / STFT buffer layout as the desktop app,
 *    using backstage/src/stft.c unchanged.
 *****************************************************************************/

#include <jni.h>
#include <math.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#include "stft.h"

#define TAG "AudioMothLive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define MAX(a, b) ((a) > (b) ? (a) : (b))

/* Constants shared with backstage.c */

#define DEFAULT_SAMPLE_RATE         48000
#define AUDIO_BUFFER_SIZE           (1 << 25)
#define STFT_INPUT_SAMPLES          512
#define STFT_INPUT_OUTPUT_RATIO     2
#define MILLISECONDS_IN_SECOND      1000

/* USB constants */

#define NUMBER_OF_URBS              16
#define PACKETS_PER_URB             8

/* Engine states reported to the UI */

#define STATE_STOPPED               0
#define STATE_RUNNING               1
#define STATE_ERROR                 2

/* Buffers */

static int16_t *audioBuffer;

static float *stftBuffer;

/* Ring buffer state (same meaning as in backstage.c) */

static int32_t audioBufferIndex;

static int32_t audioBufferWriteIndex;

static int64_t audioBufferSampleCount;

static int64_t audioBufferStartTime;

static pthread_mutex_t audioBufferMutex = PTHREAD_MUTEX_INITIALIZER;

/* Sample rates */

static int32_t inputDeviceSampleRate = 384000;

static int32_t currentSampleRate = DEFAULT_SAMPLE_RATE;

static int32_t requestedSampleRate = DEFAULT_SAMPLE_RATE;

/* Flags */

static bool restartPending = true;

static bool redrawPending = true;

static int32_t engineState = STATE_STOPPED;

/* Capture thread */

static pthread_t captureThread;

static bool captureThreadRunning;

static volatile bool stopRequested;

static int usbFd = -1;

static int usbEndpoint;

static int usbMaxPacketSize;

static struct usbdevfs_urb *urbs[NUMBER_OF_URBS];

/* Time helper */

static int64_t getMillisecondUTC(void) {

    struct timespec ts;

    clock_gettime(CLOCK_REALTIME, &ts);

    return (int64_t)ts.tv_sec * MILLISECONDS_IN_SECOND + ts.tv_nsec / 1000000;

}

static int64_t getLocalTimeOffsetMilliseconds(void) {

    time_t now = time(NULL);

    struct tm local;

    localtime_r(&now, &local);

    return (int64_t)local.tm_gmtoff * MILLISECONDS_IN_SECOND;

}

#define ROUNDED_DIV(a, b) (((a) + (b) / 2) / (b))

/* Resampler: direct port of capture_data_callback() in backstage.c */

static void processSamples(int16_t *inputBuffer, uint32_t frameCount) {

    static int32_t resampleCounter = 0;

    static double resamplePosition = 0;

    static double resampleNextSample = 0;

    static double resampleAccumulator = 0;

    static double resampleCurrentSample = 0;

    int64_t startTime = 0;

    int32_t increment = 0;

    /* Handle restart (start of stream, sample rate change) */

    pthread_mutex_lock(&audioBufferMutex);

    bool restart = restartPending;

    if (restart) {

        restartPending = false;

        currentSampleRate = MIN(requestedSampleRate, inputDeviceSampleRate);

        requestedSampleRate = currentSampleRate;

        audioBufferSampleCount = 0;

        audioBufferIndex -= audioBufferIndex % STFT_INPUT_SAMPLES;

        audioBufferWriteIndex = audioBufferIndex;

        redrawPending = true;

    }

    pthread_mutex_unlock(&audioBufferMutex);

    if (restart) {

        startTime = getMillisecondUTC();

        resampleCounter = 0;

        resamplePosition = 0;

        resampleNextSample = 0;

        resampleAccumulator = 0;

        resampleCurrentSample = 0;

        LOGI("Engine restart: device %d Hz, current %d Hz", inputDeviceSampleRate, currentSampleRate);

    }

    /* Note: integer division before ceil(), exactly as in backstage.c */

    int32_t sampleRateDivider = (int32_t)ceil(inputDeviceSampleRate / currentSampleRate);

    int32_t interpolationSampleRate = sampleRateDivider * currentSampleRate;

    double step = (double)inputDeviceSampleRate / (double)interpolationSampleRate;

    for (uint32_t i = 0; i < frameCount; i += 1) {

        resampleCurrentSample = resampleNextSample;

        resampleNextSample = inputBuffer[i];

        while (resamplePosition < 1.0) {

            resampleAccumulator += resampleCurrentSample + resamplePosition * (resampleNextSample - resampleCurrentSample);

            resampleCounter += 1;

            if (resampleCounter == sampleRateDivider) {

                double sample = MAX(INT16_MIN, MIN(INT16_MAX, round(resampleAccumulator / (double)sampleRateDivider)));

                audioBuffer[audioBufferIndex] = (int16_t)sample;

                if (audioBufferIndex % STFT_INPUT_SAMPLES == STFT_INPUT_SAMPLES - 1) {

                    int32_t startIndex = audioBufferIndex - STFT_INPUT_SAMPLES + 1;

                    STFT_transform(audioBuffer, startIndex, stftBuffer, startIndex / STFT_INPUT_OUTPUT_RATIO);

                    increment += STFT_INPUT_SAMPLES;

                }

                audioBufferIndex = (audioBufferIndex + 1) % AUDIO_BUFFER_SIZE;

                resampleAccumulator = 0;

                resampleCounter = 0;

            }

            resamplePosition += step;

        }

        resamplePosition -= 1.0;

    }

    pthread_mutex_lock(&audioBufferMutex);

    audioBufferWriteIndex = (audioBufferWriteIndex + increment) % AUDIO_BUFFER_SIZE;

    if (restart) audioBufferStartTime = startTime;

    audioBufferSampleCount += increment;

    pthread_mutex_unlock(&audioBufferMutex);

}

/* USB streaming */

static void resetUrb(struct usbdevfs_urb *urb) {

    urb->status = 0;
    urb->actual_length = 0;
    urb->error_count = 0;

    for (int i = 0; i < PACKETS_PER_URB; i += 1) {
        urb->iso_frame_desc[i].length = usbMaxPacketSize;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }

}

static void freeUrbs(void) {

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) {
        if (urbs[i]) { free(urbs[i]->buffer); free(urbs[i]); urbs[i] = NULL; }
    }

}

static bool allocateUrbs(void) {

    size_t size = sizeof(struct usbdevfs_urb) + PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) {

        urbs[i] = calloc(1, size);
        if (urbs[i] == NULL) { freeUrbs(); return false; }

        urbs[i]->buffer = malloc(PACKETS_PER_URB * usbMaxPacketSize);
        if (urbs[i]->buffer == NULL) { free(urbs[i]); urbs[i] = NULL; freeUrbs(); return false; }

        urbs[i]->type = USBDEVFS_URB_TYPE_ISO;
        urbs[i]->endpoint = (unsigned char)usbEndpoint;
        urbs[i]->flags = USBDEVFS_URB_ISO_ASAP;
        urbs[i]->buffer_length = PACKETS_PER_URB * usbMaxPacketSize;
        urbs[i]->number_of_packets = PACKETS_PER_URB;

    }

    return true;

}

static void *captureThreadFunction(void *arg) {

    (void)arg;

    int submitted = 0;

    int16_t *samples = malloc(PACKETS_PER_URB * usbMaxPacketSize);

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) {

        resetUrb(urbs[i]);

        if (ioctl(usbFd, USBDEVFS_SUBMITURB, urbs[i]) < 0) {
            LOGE("SUBMITURB failed: %s", strerror(errno));
            engineState = STATE_ERROR;
            goto done;
        }

        submitted += 1;

    }

    engineState = STATE_RUNNING;

    while (!stopRequested) {

        struct usbdevfs_urb *urb = NULL;

        if (ioctl(usbFd, USBDEVFS_REAPURB, &urb) < 0) {
            if (errno == EINTR) continue;
            LOGE("REAPURB failed: %s", strerror(errno));
            if (!stopRequested) engineState = STATE_ERROR;
            submitted = 0;
            break;
        }

        submitted -= 1;

        /* Gather packet payloads into one contiguous block */

        uint8_t *base = (uint8_t*)urb->buffer;

        uint32_t bytes = 0;

        for (int i = 0; i < urb->number_of_packets; i += 1) {

            struct usbdevfs_iso_packet_desc *pd = &urb->iso_frame_desc[i];

            if (pd->status != 0 || pd->actual_length == 0) continue;

            memcpy((uint8_t*)samples + bytes, base + (size_t)i * usbMaxPacketSize, pd->actual_length & ~1u);

            bytes += pd->actual_length & ~1u;

        }

        if (bytes > 0) processSamples(samples, bytes / 2);

        if (stopRequested) break;

        resetUrb(urb);

        if (ioctl(usbFd, USBDEVFS_SUBMITURB, urb) < 0) {
            LOGE("re-SUBMITURB failed: %s", strerror(errno));
            engineState = STATE_ERROR;
            break;
        }

        submitted += 1;

    }

done:

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) ioctl(usbFd, USBDEVFS_DISCARDURB, urbs[i]);

    while (submitted > 0) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(usbFd, USBDEVFS_REAPURB, &urb) < 0) break;
        submitted -= 1;
    }

    free(samples);

    if (engineState == STATE_RUNNING) engineState = STATE_STOPPED;

    LOGI("Capture thread finished (state %d)", engineState);

    return NULL;

}

/* JNI */

#define JNI_FN(name) Java_info_openacousticdevices_audiomothlive_Engine_##name

JNIEXPORT jboolean JNICALL JNI_FN(nativeInitialise)(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    if (audioBuffer != NULL) return JNI_TRUE;

    audioBuffer = calloc(AUDIO_BUFFER_SIZE, sizeof(int16_t));

    stftBuffer = calloc(AUDIO_BUFFER_SIZE / STFT_INPUT_OUTPUT_RATIO, sizeof(float));

    if (audioBuffer == NULL || stftBuffer == NULL) {
        free(audioBuffer); free(stftBuffer);
        audioBuffer = NULL; stftBuffer = NULL;
        LOGE("Failed to allocate audio buffers");
        return JNI_FALSE;
    }

    STFT_initialise();

    return JNI_TRUE;

}

JNIEXPORT jobject JNICALL JNI_FN(nativeGetAudioBuffer)(JNIEnv *env, jclass clazz) {

    (void)clazz;

    return (*env)->NewDirectByteBuffer(env, audioBuffer, (jlong)AUDIO_BUFFER_SIZE * sizeof(int16_t));

}

JNIEXPORT jobject JNICALL JNI_FN(nativeGetStftBuffer)(JNIEnv *env, jclass clazz) {

    (void)clazz;

    return (*env)->NewDirectByteBuffer(env, stftBuffer, (jlong)AUDIO_BUFFER_SIZE / STFT_INPUT_OUTPUT_RATIO * sizeof(float));

}

JNIEXPORT jboolean JNICALL JNI_FN(nativeStart)(JNIEnv *env, jclass clazz, jint fd, jint endpoint, jint maxPacketSize, jint deviceSampleRate) {

    (void)env; (void)clazz;

    if (captureThreadRunning || audioBuffer == NULL) return JNI_FALSE;

    usbFd = fd;
    usbEndpoint = endpoint;
    usbMaxPacketSize = maxPacketSize;

    if (!allocateUrbs()) return JNI_FALSE;

    pthread_mutex_lock(&audioBufferMutex);

    inputDeviceSampleRate = deviceSampleRate;

    restartPending = true;

    pthread_mutex_unlock(&audioBufferMutex);

    stopRequested = false;

    engineState = STATE_STOPPED;

    if (pthread_create(&captureThread, NULL, captureThreadFunction, NULL) != 0) {
        freeUrbs();
        return JNI_FALSE;
    }

    captureThreadRunning = true;

    LOGI("Engine started: fd=%d ep=0x%02x mps=%d device rate=%d", fd, endpoint, maxPacketSize, deviceSampleRate);

    return JNI_TRUE;

}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    if (!captureThreadRunning) return;

    stopRequested = true;

    /* Cancel outstanding transfers so a blocked REAPURB returns promptly */

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) if (urbs[i]) ioctl(usbFd, USBDEVFS_DISCARDURB, urbs[i]);

    pthread_join(captureThread, NULL);

    captureThreadRunning = false;

    freeUrbs();

    usbFd = -1;

    engineState = STATE_STOPPED;

    LOGI("Engine stopped");

}

JNIEXPORT void JNICALL JNI_FN(nativeChangeSampleRate)(JNIEnv *env, jclass clazz, jint sampleRate) {

    (void)env; (void)clazz;

    pthread_mutex_lock(&audioBufferMutex);

    requestedSampleRate = sampleRate;

    if (!captureThreadRunning) currentSampleRate = MIN(sampleRate, inputDeviceSampleRate);

    restartPending = true;

    pthread_mutex_unlock(&audioBufferMutex);

}

JNIEXPORT void JNICALL JNI_FN(nativeClear)(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    pthread_mutex_lock(&audioBufferMutex);

    if (currentSampleRate > 0) audioBufferStartTime += ROUNDED_DIV(audioBufferSampleCount * MILLISECONDS_IN_SECOND, (int64_t)currentSampleRate);

    audioBufferSampleCount = 0;

    redrawPending = true;

    pthread_mutex_unlock(&audioBufferMutex);

}

/*
 * Fills out[] with:
 *  0 audioIndex, 1 audioCount, 2 currentSampleRate, 3 audioTime (ms, local),
 *  4 redrawRequired, 5 engineState, 6 deviceSampleRate
 */

JNIEXPORT void JNICALL JNI_FN(nativeGetFrame)(JNIEnv *env, jclass clazz, jlongArray out) {

    (void)clazz;

    jlong values[7];

    pthread_mutex_lock(&audioBufferMutex);

    int64_t count = audioBufferSampleCount;

    int32_t rate = currentSampleRate;

    values[0] = audioBufferWriteIndex;
    values[1] = count;
    values[2] = rate;
    values[3] = audioBufferStartTime + (rate > 0 ? ROUNDED_DIV(count * MILLISECONDS_IN_SECOND, (int64_t)rate) : 0);
    values[4] = redrawPending ? 1 : 0;

    redrawPending = false;

    pthread_mutex_unlock(&audioBufferMutex);

    values[3] += getLocalTimeOffsetMilliseconds();
    values[5] = engineState;
    values[6] = inputDeviceSampleRate;

    (*env)->SetLongArrayRegion(env, out, 0, 7, values);

}
