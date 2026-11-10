/****************************************************************************
 * android_logging.h
 *
 * Route backstage's stdout logging (puts/printf) to logcat, tag AudioMothLive.
 * Included after all other headers in the generated backstage core.
 *****************************************************************************/

#ifndef __ANDROID_LOGGING_H
#define __ANDROID_LOGGING_H

#include <stdio.h>
#include <android/log.h>

#define puts(s)         __android_log_print(ANDROID_LOG_INFO, "AudioMothLive", "%s", (s))
#define printf(...)     __android_log_print(ANDROID_LOG_INFO, "AudioMothLive", __VA_ARGS__)

#endif /* __ANDROID_LOGGING_H */
