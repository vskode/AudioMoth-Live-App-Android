/****************************************************************************
 * plotter.c
 *
 * Port of js/plotter.js (desktop AudioMoth Live App) to C. Draws the
 * scrolling waveform and spectrogram into Android ARGB_8888 bitmaps.
 *
 * Android ARGB_8888 bitmaps store bytes as R,G,B,A, which read as a
 * little-endian uint32 is 0xAABBGGRR, exactly like the Uint32Array view of
 * an HTML canvas ImageData used by the desktop app. The colour tables and
 * pixel constants are therefore identical to js/colourMap.js and
 * js/constants.js.
 *****************************************************************************/

#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <android/bitmap.h>

#define MAX(a, b) ((a) > (b) ? (a) : (b))
#define MIN(a, b) ((a) < (b) ? (a) : (b))

/* Constants from js/constants.js */

#define STATIC_COLOUR_MIN           2.0
#define STATIC_COLOUR_MAX           15.0

#define LOW_AMP_COLOUR_MIN          -3.0
#define LOW_AMP_COLOUR_MAX          12.0

#define PIXEL_COLOUR                ((255u << 24) | (153u << 16) | (77u << 8) | 0u)
#define PIXEL_COLOUR_NIGHT          ((220u << 24) | (250u << 16) | (170u << 8) | 0u)
#define PIXEL_COLOUR_MONOCHROME     ((255u << 24) | (0u << 16) | (0u << 8) | 0u)
#define PIXEL_COLOUR_MONOCHROME_NIGHT ((255u << 24) | (255u << 16) | (255u << 8) | 255u)

#define STFT_INPUT_SAMPLES          512
#define STFT_OUTPUT_SAMPLES         256
#define STFT_INPUT_OUTPUT_RATIO     (STFT_INPUT_SAMPLES / STFT_OUTPUT_SAMPLES)

#define INT16_MIN_VALUE             -32768
#define INT16_MAX_VALUE             32767

#define AUDIO_BUFFER_SIZE           (1 << 25)

#define COLOUR_MAP_DEFAULT          0
#define COLOUR_MAP_MONOCHROME       1
#define COLOUR_MAP_INVERSE_MONOCHROME 2

#define UPDATE_BOTH                 0
#define UPDATE_WAVEFORM             1
#define UPDATE_SPECTROGRAM          2

/* Colour map from js/colourMap.js (rgbColours) */

static const uint8_t rgbColours[256][3] = {{0, 0, 131}, {0, 2, 132}, {0, 4, 133}, {0, 6, 135}, {0, 8, 136}, {0, 9, 137}, {0, 11, 138}, {0, 13, 140}, {0, 15, 141}, {0, 17, 142}, {0, 19, 143}, {0, 21, 144}, {0, 23, 146}, {0, 24, 147}, {0, 26, 148}, {0, 28, 149}, {0, 30, 151}, {0, 32, 152}, {0, 34, 153}, {0, 36, 154}, {0, 38, 155}, {0, 39, 157}, {0, 41, 158}, {0, 43, 159}, {0, 45, 160}, {0, 47, 161}, {0, 49, 163}, {0, 51, 164}, {0, 53, 165}, {0, 54, 166}, {0, 56, 168}, {0, 58, 169}, {0, 60, 170}, {0, 63, 171}, {0, 66, 173}, {0, 69, 174}, {0, 72, 175}, {0, 75, 177}, {0, 79, 178}, {1, 82, 179}, {1, 85, 181}, {1, 88, 182}, {1, 91, 183}, {1, 94, 185}, {1, 97, 186}, {1, 100, 188}, {1, 103, 189}, {1, 106, 190}, {1, 110, 192}, {1, 113, 193}, {1, 116, 194}, {2, 119, 196}, {2, 122, 197}, {2, 125, 198}, {2, 128, 200}, {2, 131, 201}, {2, 134, 202}, {2, 137, 204}, {2, 140, 205}, {2, 144, 206}, {2, 147, 208}, {2, 150, 209}, {2, 153, 210}, {2, 156, 212}, {3, 159, 213}, {3, 162, 215}, {3, 165, 216}, {3, 168, 217}, {3, 171, 219}, {3, 175, 220}, {3, 178, 221}, {3, 181, 223}, {3, 184, 224}, {3, 187, 225}, {3, 190, 227}, {3, 193, 228}, {3, 196, 229}, {4, 199, 231}, {4, 202, 232}, {4, 205, 233}, {4, 209, 235}, {4, 212, 236}, {4, 215, 237}, {4, 218, 239}, {4, 221, 240}, {4, 224, 242}, {4, 227, 243}, {4, 230, 244}, {4, 233, 246}, {5, 236, 247}, {5, 240, 248}, {5, 243, 250}, {5, 246, 251}, {5, 249, 252}, {5, 252, 254}, {5, 255, 255}, {9, 255, 251}, {13, 255, 247}, {17, 255, 243}, {21, 255, 239}, {25, 255, 235}, {28, 255, 231}, {32, 255, 227}, {36, 255, 223}, {40, 255, 219}, {44, 255, 215}, {48, 255, 211}, {52, 255, 207}, {56, 255, 203}, {60, 255, 199}, {64, 255, 195}, {68, 255, 191}, {71, 255, 187}, {75, 255, 183}, {79, 255, 179}, {83, 255, 175}, {87, 255, 171}, {91, 255, 167}, {95, 255, 163}, {99, 255, 159}, {103, 255, 155}, {107, 255, 151}, {110, 255, 147}, {114, 255, 143}, {118, 255, 139}, {122, 255, 135}, {126, 255, 131}, {130, 255, 128}, {134, 255, 124}, {138, 255, 120}, {142, 255, 116}, {146, 255, 112}, {150, 255, 108}, {153, 255, 104}, {157, 255, 100}, {161, 255, 96}, {165, 255, 92}, {169, 255, 88}, {173, 255, 84}, {177, 255, 80}, {181, 255, 76}, {185, 255, 72}, {189, 255, 68}, {193, 255, 64}, {196, 255, 60}, {200, 255, 56}, {204, 255, 52}, {208, 255, 48}, {212, 255, 44}, {216, 255, 40}, {220, 255, 36}, {224, 255, 32}, {228, 255, 28}, {232, 255, 24}, {235, 255, 20}, {239, 255, 16}, {243, 255, 12}, {247, 255, 8}, {251, 255, 4}, {255, 255, 0}, {255, 251, 0}, {255, 247, 0}, {255, 243, 0}, {255, 239, 0}, {255, 235, 0}, {255, 231, 0}, {254, 227, 0}, {254, 223, 0}, {254, 219, 0}, {254, 215, 0}, {254, 210, 0}, {254, 206, 0}, {254, 202, 0}, {254, 198, 0}, {254, 194, 0}, {254, 190, 0}, {254, 186, 0}, {254, 182, 0}, {253, 178, 0}, {253, 174, 0}, {253, 170, 0}, {253, 166, 0}, {253, 162, 0}, {253, 158, 0}, {253, 154, 0}, {253, 150, 0}, {253, 146, 0}, {253, 142, 0}, {253, 138, 0}, {253, 134, 0}, {253, 130, 0}, {252, 125, 0}, {252, 121, 0}, {252, 117, 0}, {252, 113, 0}, {252, 109, 0}, {252, 105, 0}, {252, 101, 0}, {252, 97, 0}, {252, 93, 0}, {252, 89, 0}, {252, 85, 0}, {252, 81, 0}, {252, 77, 0}, {251, 73, 0}, {251, 69, 0}, {251, 65, 0}, {251, 61, 0}, {251, 57, 0}, {251, 53, 0}, {251, 49, 0}, {251, 45, 0}, {251, 40, 0}, {251, 36, 0}, {251, 32, 0}, {251, 28, 0}, {250, 24, 0}, {250, 20, 0}, {250, 16, 0}, {250, 12, 0}, {250, 8, 0}, {250, 4, 0}, {250, 0, 0}, {246, 0, 0}, {242, 0, 0}, {239, 0, 0}, {235, 0, 0}, {231, 0, 0}, {227, 0, 0}, {223, 0, 0}, {220, 0, 0}, {216, 0, 0}, {212, 0, 0}, {208, 0, 0}, {204, 0, 0}, {200, 0, 0}, {197, 0, 0}, {193, 0, 0}, {189, 0, 0}, {185, 0, 0}, {181, 0, 0}, {178, 0, 0}, {174, 0, 0}, {170, 0, 0}, {166, 0, 0}, {162, 0, 0}, {159, 0, 0}, {155, 0, 0}, {151, 0, 0}, {147, 0, 0}, {143, 0, 0}, {139, 0, 0}, {136, 0, 0}, {132, 0, 0}, {128, 0, 0}, {128, 0, 0}};

/* Plotter state (module-level variables in plotter.js) */

static uint32_t colourTable[256];

static int32_t colourMapIndex = -1;

static double stftArray[STFT_OUTPUT_SAMPLES];

static int32_t *stftIndexLookup;

static int32_t stftIndexLookupHeight;

static int32_t columnMax = INT16_MIN_VALUE;

static int32_t columnMin = INT16_MAX_VALUE;

static int64_t lastCount = 0;

static bool newColumn = true;

static double positionInColumn = 0;

static bool nightMode = false;

/* Bitmap views for the current update */

static uint32_t *waveformPixels;

static int32_t waveformStride;

static int32_t waveformPixelHeight;

static uint32_t *spectrogramPixels;

static int32_t spectrogramStride;

static int32_t spectrogramPixelHeight;

static int32_t pixelWidth;

/* Reset functions */

static void resetColourMap(int32_t newColourMapIndex) {

    colourMapIndex = newColourMapIndex;

    for (int32_t i = 0; i < 256; i += 1) {

        uint32_t r, g, b;

        if (colourMapIndex == COLOUR_MAP_MONOCHROME) {
            r = g = b = (uint32_t)i;
        } else if (colourMapIndex == COLOUR_MAP_INVERSE_MONOCHROME) {
            r = g = b = (uint32_t)(255 - i);
        } else {
            r = rgbColours[i][0]; g = rgbColours[i][1]; b = rgbColours[i][2];
        }

        colourTable[i] = (0xFFu << 24) | (b << 16) | (g << 8) | r;

    }

}

static void resetStftIndexLookup(int32_t height) {

    if (height == stftIndexLookupHeight && stftIndexLookup != NULL) return;

    free(stftIndexLookup);

    stftIndexLookup = malloc(sizeof(int32_t) * height);

    stftIndexLookupHeight = height;

    for (int32_t i = 0; i < height; i += 1) {

        stftIndexLookup[i] = STFT_OUTPUT_SAMPLES - 1 - (int32_t)round((double)i * (STFT_OUTPUT_SAMPLES - 2) / (double)(height - 1));

    }

}

/* Drawing functions */

static void scrollOrClearColumns(uint32_t *pixels, int32_t stride, int32_t pixelHeight, bool redraw, int32_t numberOfColumns) {

    if (redraw) {

        for (int32_t row = 0; row < pixelHeight; row += 1) {

            memset(pixels + (size_t)row * stride, 0, sizeof(uint32_t) * (size_t)MAX(0, pixelWidth - numberOfColumns));

        }

    } else if (numberOfColumns > 0) {

        for (int32_t row = 0; row < pixelHeight; row += 1) {

            uint32_t *line = pixels + (size_t)row * stride;

            if (numberOfColumns < pixelWidth) memmove(line, line + numberOfColumns, sizeof(uint32_t) * (size_t)(pixelWidth - numberOfColumns));

        }

    }

}

static void drawWaveformColumn(int32_t columnOffset) {

    int32_t halfHeight = (int32_t)round(waveformPixelHeight / 2.0) - 1;

    double multiplier = -(double)halfHeight / (double)INT16_MIN_VALUE;

    int32_t minimum = (int32_t)round(columnMin * multiplier + halfHeight);
    int32_t maximum = (int32_t)round(columnMax * multiplier + halfHeight);

    uint32_t colour;

    if (nightMode) {
        colour = colourMapIndex == COLOUR_MAP_DEFAULT ? PIXEL_COLOUR_NIGHT : PIXEL_COLOUR_MONOCHROME_NIGHT;
    } else {
        colour = colourMapIndex == COLOUR_MAP_DEFAULT ? PIXEL_COLOUR : PIXEL_COLOUR_MONOCHROME;
    }

    int32_t col = pixelWidth - columnOffset;

    if (col >= 0 && col < pixelWidth) {

        for (int32_t row = 0; row < waveformPixelHeight; row += 1) {

            bool pixelIsBlank = row < minimum || row > maximum;

            waveformPixels[(size_t)row * waveformStride + col] = pixelIsBlank ? 0 : colour;

        }

    }

    columnMax = INT16_MIN_VALUE;
    columnMin = INT16_MAX_VALUE;

}

static void drawSpectrogramColumn(int32_t columnOffset, bool lowAmpColourScaleEnabled) {

    double colourMin = lowAmpColourScaleEnabled ? LOW_AMP_COLOUR_MIN : STATIC_COLOUR_MIN;
    double colourMax = lowAmpColourScaleEnabled ? LOW_AMP_COLOUR_MAX : STATIC_COLOUR_MAX;

    int32_t col = pixelWidth - columnOffset;

    if (col < 0 || col >= pixelWidth) return;

    for (int32_t row = 0; row < spectrogramPixelHeight; row += 1) {

        double value = stftArray[stftIndexLookup[row]];

        /* log2(0) = -inf for silent input: treat as minimum colour */

        int32_t colourIndex = isfinite(value) ? (int32_t)round(255.0 * (value - colourMin) / (colourMax - colourMin)) : 0;

        colourIndex = MAX(colourIndex, 0);
        colourIndex = MIN(colourIndex, 255);

        spectrogramPixels[(size_t)row * spectrogramStride + col] = colourTable[colourIndex];

    }

}

/* Main update function: port of exports.update in plotter.js */

static void update(const int16_t *audioBuffer, const float *stftBuffer, int32_t mode, bool redraw, int64_t index, int64_t count, int64_t displayWidthSamples, bool isNightMode, bool lowAmpColourScaleEnabled, int32_t newColourMapIndex) {

    if (nightMode != isNightMode) redraw = true;

    nightMode = isNightMode;

    if (colourMapIndex != newColourMapIndex) {

        resetColourMap(newColourMapIndex);

        redraw = true;

    }

    int64_t numberOfSamples = count - lastCount;

    if (numberOfSamples >= displayWidthSamples) redraw = true;

    if (redraw) {

        numberOfSamples = MIN(count, displayWidthSamples);

        for (int32_t i = 0; i < STFT_OUTPUT_SAMPLES; i += 1) stftArray[i] = 0;

        columnMax = INT16_MIN_VALUE;
        columnMin = INT16_MAX_VALUE;

        positionInColumn = 0;

    }

    if (numberOfSamples < 0) numberOfSamples = 0;

    const int64_t length = AUDIO_BUFFER_SIZE;

    int64_t offset = ((length + index - numberOfSamples) % length + length) % length;

    /* Calculate step size based on samples per column */

    double stepSize = (double)pixelWidth / (double)displayWidthSamples;

    /* Calculate expected number of columns */

    int32_t expectedNumberOfColumns = (int32_t)floor(positionInColumn + numberOfSamples * stepSize);

    /* Scroll or clear */

    if (mode == UPDATE_BOTH || mode == UPDATE_WAVEFORM) scrollOrClearColumns(waveformPixels, waveformStride, waveformPixelHeight, redraw, expectedNumberOfColumns);

    if (mode == UPDATE_BOTH || mode == UPDATE_SPECTROGRAM) scrollOrClearColumns(spectrogramPixels, spectrogramStride, spectrogramPixelHeight, redraw, expectedNumberOfColumns);

    /* Main loop */

    int32_t numberOfColumns = 0;

    for (int64_t i = 0; i < numberOfSamples; i += 1) {

        int64_t idx = (offset + i) % length;

        int32_t sample = -(int32_t)audioBuffer[idx];

        columnMax = sample > columnMax ? sample : columnMax;
        columnMin = sample < columnMin ? sample : columnMin;

        if (idx % STFT_INPUT_SAMPLES == 0) {

            int64_t stftIndex = idx / STFT_INPUT_OUTPUT_RATIO;

            for (int32_t k = 0; k < STFT_OUTPUT_SAMPLES; k += 1) {

                double data = stftBuffer[stftIndex + k];

                stftArray[k] = newColumn || data > stftArray[k] ? data : stftArray[k];

            }

            newColumn = false;

        }

        positionInColumn += stepSize;

        if (positionInColumn >= 1) {

            if (numberOfColumns < expectedNumberOfColumns) {

                if (mode == UPDATE_BOTH || mode == UPDATE_WAVEFORM) drawWaveformColumn(expectedNumberOfColumns - numberOfColumns);

                if (mode == UPDATE_BOTH || mode == UPDATE_SPECTROGRAM) drawSpectrogramColumn(expectedNumberOfColumns - numberOfColumns, lowAmpColourScaleEnabled);

                numberOfColumns += 1;

                positionInColumn -= 1;

                newColumn = true;

            }

        }

    }

    /* Check for missing column due to rounding errors */

    if (numberOfColumns < expectedNumberOfColumns) {

        if (mode == UPDATE_BOTH || mode == UPDATE_WAVEFORM) drawWaveformColumn(expectedNumberOfColumns - numberOfColumns);

        if (mode == UPDATE_BOTH || mode == UPDATE_SPECTROGRAM) drawSpectrogramColumn(expectedNumberOfColumns - numberOfColumns, lowAmpColourScaleEnabled);

        positionInColumn -= 1;

        newColumn = true;

    }

    lastCount = count;

}

/* JNI */

#define JNI_FN(name) Java_info_openacousticdevices_audiomothlive_Plotter_##name

/* Returns true if any columns changed (the views should be invalidated) */

JNIEXPORT jboolean JNICALL JNI_FN(nativeUpdate)(JNIEnv *env, jclass clazz,
        jobject audioByteBuffer, jobject stftByteBuffer,
        jobject waveformBitmap, jobject spectrogramBitmap,
        jboolean redraw, jlong index, jlong count, jlong displayWidthSamples,
        jboolean isNightMode, jboolean lowAmpColourScaleEnabled, jint newColourMapIndex) {

    (void)clazz;

    const int16_t *audioBuffer = (*env)->GetDirectBufferAddress(env, audioByteBuffer);
    const float *stftBuffer = (*env)->GetDirectBufferAddress(env, stftByteBuffer);

    if (audioBuffer == NULL || stftBuffer == NULL || displayWidthSamples <= 0) return JNI_FALSE;

    AndroidBitmapInfo waveformInfo, spectrogramInfo;

    if (AndroidBitmap_getInfo(env, waveformBitmap, &waveformInfo) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, spectrogramBitmap, &spectrogramInfo) < 0) return JNI_FALSE;

    if (waveformInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || spectrogramInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    void *wp, *sp;

    if (AndroidBitmap_lockPixels(env, waveformBitmap, &wp) < 0) return JNI_FALSE;

    if (AndroidBitmap_lockPixels(env, spectrogramBitmap, &sp) < 0) {
        AndroidBitmap_unlockPixels(env, waveformBitmap);
        return JNI_FALSE;
    }

    /* Both plots share the same width, as in the desktop app */

    pixelWidth = (int32_t)MIN(waveformInfo.width, spectrogramInfo.width);

    waveformPixels = wp;
    waveformStride = (int32_t)(waveformInfo.stride / 4);
    waveformPixelHeight = (int32_t)waveformInfo.height;

    spectrogramPixels = sp;
    spectrogramStride = (int32_t)(spectrogramInfo.stride / 4);
    spectrogramPixelHeight = (int32_t)spectrogramInfo.height;

    if (colourMapIndex < 0) resetColourMap(newColourMapIndex);

    resetStftIndexLookup(spectrogramPixelHeight);

    int64_t before = lastCount;

    update(audioBuffer, stftBuffer, UPDATE_BOTH, redraw, index, count, displayWidthSamples, isNightMode, lowAmpColourScaleEnabled, newColourMapIndex);

    AndroidBitmap_unlockPixels(env, spectrogramBitmap);
    AndroidBitmap_unlockPixels(env, waveformBitmap);

    return redraw || count != before;

}
