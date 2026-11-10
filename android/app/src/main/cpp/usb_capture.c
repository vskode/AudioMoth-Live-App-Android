/****************************************************************************
 * usb_capture.c
 *
 * Phase 0 proof of concept: capture audio from a USB Audio Class device
 * (AudioMoth USB Microphone) by submitting isochronous URBs directly to the
 * Linux usbfs file descriptor obtained from Android's UsbDeviceConnection.
 *
 * This bypasses the Android audio stack (which resamples to 48 kHz), so the
 * device's native sample rate (up to 384 kHz) is preserved.
 *
 * The Java side is responsible for claiming the AudioStreaming interface
 * (detaching the kernel driver) and selecting the streaming alternate
 * setting before calling nativeCapture().
 *****************************************************************************/

#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define TAG "AudioMothLive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NUMBER_OF_URBS          16
#define PACKETS_PER_URB         32      /* 32 x 1 ms full-speed frames */

static volatile int stopRequested = 0;

/* WAV header */

static void writeLE16(FILE *f, uint16_t v) { uint8_t b[2] = { v & 0xff, v >> 8 }; fwrite(b, 1, 2, f); }

static void writeLE32(FILE *f, uint32_t v) { uint8_t b[4] = { v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, v >> 24 }; fwrite(b, 1, 4, f); }

static void writeWavHeader(FILE *f, uint32_t sampleRate, uint16_t channels, uint16_t bitsPerSample, uint32_t dataBytes) {

    uint16_t blockAlign = channels * bitsPerSample / 8;

    fseek(f, 0, SEEK_SET);
    fwrite("RIFF", 1, 4, f);
    writeLE32(f, 36 + dataBytes);
    fwrite("WAVEfmt ", 1, 8, f);
    writeLE32(f, 16);
    writeLE16(f, 1);
    writeLE16(f, channels);
    writeLE32(f, sampleRate);
    writeLE32(f, sampleRate * blockAlign);
    writeLE16(f, blockAlign);
    writeLE16(f, bitsPerSample);
    fwrite("data", 1, 4, f);
    writeLE32(f, dataBytes);

}

static double nowSeconds(void) {

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;

}

static struct usbdevfs_urb *allocateUrb(uint8_t endpoint, int packetSize) {

    size_t size = sizeof(struct usbdevfs_urb) + PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);

    struct usbdevfs_urb *urb = calloc(1, size);
    if (urb == NULL) return NULL;

    urb->buffer = malloc(PACKETS_PER_URB * packetSize);
    if (urb->buffer == NULL) { free(urb); return NULL; }

    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = endpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer_length = PACKETS_PER_URB * packetSize;
    urb->number_of_packets = PACKETS_PER_URB;

    return urb;

}

static void resetUrb(struct usbdevfs_urb *urb, int packetSize) {

    urb->status = 0;
    urb->actual_length = 0;
    urb->error_count = 0;
    for (int i = 0; i < PACKETS_PER_URB; i += 1) {
        urb->iso_frame_desc[i].length = packetSize;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }

}

/*
 * Returns a string with capture statistics (or an error message starting
 * with "ERROR").
 */

JNIEXPORT jstring JNICALL
Java_info_openacousticdevices_audiomothlive_UsbCapture_nativeCapture(JNIEnv *env, jclass clazz,
        jint fd, jint endpoint, jint maxPacketSize, jint sampleRate, jint channels,
        jint bitsPerSample, jdouble seconds, jstring jpath) {

    (void)clazz;

    char result[1024];

    stopRequested = 0;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    FILE *f = fopen(path, "wb");
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (f == NULL) {
        snprintf(result, sizeof(result), "ERROR: cannot open output file (%s)", strerror(errno));
        return (*env)->NewStringUTF(env, result);
    }

    writeWavHeader(f, sampleRate, channels, bitsPerSample, 0);

    const uint32_t bytesPerFrame = channels * bitsPerSample / 8;
    const uint64_t targetBytes = (uint64_t)(seconds * sampleRate) * bytesPerFrame;

    struct usbdevfs_urb *urbs[NUMBER_OF_URBS];
    int submitted = 0;

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) {
        urbs[i] = allocateUrb((uint8_t)endpoint, maxPacketSize);
        if (urbs[i] == NULL) {
            snprintf(result, sizeof(result), "ERROR: out of memory");
            fclose(f);
            for (int j = 0; j < i; j += 1) { free(urbs[j]->buffer); free(urbs[j]); }
            return (*env)->NewStringUTF(env, result);
        }
    }

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) {
        resetUrb(urbs[i], maxPacketSize);
        if (ioctl(fd, USBDEVFS_SUBMITURB, urbs[i]) < 0) {
            LOGE("SUBMITURB %d failed: %s", i, strerror(errno));
            snprintf(result, sizeof(result), "ERROR: submitting isochronous transfer failed (%s)", strerror(errno));
            goto cleanup;
        }
        submitted += 1;
    }

    uint64_t bytesWritten = 0;
    uint64_t packetsTotal = 0, packetsEmpty = 0, packetsError = 0, urbErrors = 0;
    uint32_t minPacket = UINT32_MAX, maxPacket = 0;
    int32_t peak = 0;

    double startTime = nowSeconds();

    LOGI("Capture started: ep=0x%02x mps=%d rate=%d ch=%d bits=%d", endpoint, maxPacketSize, sampleRate, channels, bitsPerSample);

    while (bytesWritten < targetBytes && !stopRequested) {

        struct usbdevfs_urb *urb = NULL;

        if (ioctl(fd, USBDEVFS_REAPURB, &urb) < 0) {
            if (errno == EINTR) continue;
            LOGE("REAPURB failed: %s", strerror(errno));
            snprintf(result, sizeof(result), "ERROR: reaping transfer failed (%s) - device unplugged?", strerror(errno));
            submitted = 0;
            goto cleanup;
        }

        submitted -= 1;

        if (urb->status != 0) urbErrors += 1;

        uint8_t *base = (uint8_t*)urb->buffer;

        for (int i = 0; i < urb->number_of_packets; i += 1) {

            struct usbdevfs_iso_packet_desc *pd = &urb->iso_frame_desc[i];

            packetsTotal += 1;

            if (pd->status != 0) { packetsError += 1; continue; }

            uint32_t len = pd->actual_length;

            if (len == 0) { packetsEmpty += 1; continue; }

            if (len < minPacket) minPacket = len;
            if (len > maxPacket) maxPacket = len;

            uint8_t *data = base + (size_t)i * maxPacketSize;

            uint64_t remaining = targetBytes - bytesWritten;
            if (len > remaining) len = (uint32_t)remaining;

            if (bitsPerSample == 16) {
                for (uint32_t k = 0; k + 1 < len; k += 2) {
                    int32_t s = (int16_t)(data[k] | (data[k + 1] << 8));
                    if (s < 0) s = -s;
                    if (s > peak) peak = s;
                }
            }

            fwrite(data, 1, len, f);
            bytesWritten += len;

            if (bytesWritten >= targetBytes) break;

        }

        resetUrb(urb, maxPacketSize);

        if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0) {
            LOGE("re-SUBMITURB failed: %s", strerror(errno));
            snprintf(result, sizeof(result), "ERROR: resubmitting transfer failed (%s)", strerror(errno));
            goto cleanup;
        }

        submitted += 1;

    }

    double elapsed = nowSeconds() - startTime;
    double measuredRate = elapsed > 0 ? (bytesWritten / (double)bytesPerFrame) / elapsed : 0;

    snprintf(result, sizeof(result),
        "OK\n"
        "Bytes written: %llu (%.2f s of audio)\n"
        "Wall time: %.2f s\n"
        "Measured sample rate: %.0f Hz (expected %d)\n"
        "Packets: %llu total, %llu empty, %llu errors, %llu URB errors\n"
        "Packet size: min %u, max %u bytes (max packet %d)\n"
        "Peak amplitude: %d / 32767",
        (unsigned long long)bytesWritten, bytesWritten / (double)bytesPerFrame / sampleRate,
        elapsed, measuredRate, sampleRate,
        (unsigned long long)packetsTotal, (unsigned long long)packetsEmpty,
        (unsigned long long)packetsError, (unsigned long long)urbErrors,
        minPacket == UINT32_MAX ? 0 : minPacket, maxPacket, maxPacketSize, peak);

    writeWavHeader(f, sampleRate, channels, bitsPerSample, (uint32_t)bytesWritten);

    LOGI("%s", result);

cleanup:

    /* Cancel and reap any outstanding URBs before freeing them */

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) ioctl(fd, USBDEVFS_DISCARDURB, urbs[i]);

    while (submitted > 0) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(fd, USBDEVFS_REAPURB, &urb) < 0) break;
        submitted -= 1;
    }

    for (int i = 0; i < NUMBER_OF_URBS; i += 1) { free(urbs[i]->buffer); free(urbs[i]); }

    fclose(f);

    return (*env)->NewStringUTF(env, result);

}

JNIEXPORT void JNICALL
Java_info_openacousticdevices_audiomothlive_UsbCapture_nativeStop(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    stopRequested = 1;

}
