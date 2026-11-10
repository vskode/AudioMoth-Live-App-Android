/****************************************************************************
 * backstage_jni.c
 *
 * JNI versions of the exported N-API functions in backstage/src/backstage.c
 * (initialise, getFrame, changeSampleRate, clear, capture, setPause, ...).
 * The function bodies follow the desktop code; Node-API object creation is
 * replaced by filling arrays/strings polled from Kotlin (Backstage.kt).
 *****************************************************************************/

#include <string.h>

#include "generated/backstage_core.c"

#define JNI_FN(name) Java_info_openacousticdevices_audiomothlive_Backstage_##name

/* Frame array layout (keep in sync with Backstage.kt) */

#define FRAME_REDRAW_REQUIRED           0
#define FRAME_SIMULATION_RUNNING        1
#define FRAME_OLD_AUDIOMOTH_FOUND       2
#define FRAME_MAXIMUM_SAMPLE_RATE       3
#define FRAME_CURRENT_SAMPLE_RATE       4
#define FRAME_AUDIO_TIME                5
#define FRAME_AUDIO_INDEX               6
#define FRAME_AUDIO_COUNT               7
#define FRAME_CAPTURE_RESULT            8
#define FRAME_AUTOSAVE_ERROR            9
#define FRAME_WRITTEN_FILE_COUNTER      10
#define FRAME_USING_AUDIOMOTH           11
#define FRAME_LENGTH                    12

static bool initialised = false;

/* Busy-wait helpers used by the desktop code */

static bool waitForStarted(void) {

    double startTime = ma_timer_get_time_in_seconds(&timer);

    while (true) {

        pthread_mutex_lock(&stopStartMutex);

        bool threadStarted = started;

        pthread_mutex_unlock(&stopStartMutex);

        if (threadStarted) return true;

        if (ma_timer_get_time_in_seconds(&timer) - startTime > DEVICE_STOP_START_TIMEOUT) return false;

        usleep(1000);

    }

}

/* initialise() */

JNIEXPORT jboolean JNICALL JNI_FN(nativeInitialise)(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    if (initialised) return JNI_TRUE;

    bool success = true;

    puts("[BACKSTAGE] initialise");

    ma_timer_init(&timer);

    if (ma_context_init(NULL, 0, NULL, &deviceCheckContext) != MA_SUCCESS) {
        puts("[BACKSTAGE] Could not initialise device check context");
        success = false;
    }

    if (ma_context_init(NULL, 0, NULL, &playbackContext) != MA_SUCCESS) {
        puts("[BACKSTAGE] Could not initialise playback context");
        success = false;
    }

    if (Autosave_initialise(AUTOSAVE_EVENT_QUEUE_SIZE) == false) {
        puts("[BACKSTAGE] Could not initialise autosave queue");
        success = false;
    }

    STFT_initialise();

    Heterodyne_initialise(MAXIMUM_SAMPLE_RATE, 45000);

    pthread_mutex_init(&playbackMutex, NULL);
    pthread_mutex_init(&autosaveMutex, NULL);
    pthread_mutex_init(&localTimeMutex, NULL);
    pthread_mutex_init(&stopStartMutex, NULL);
    pthread_mutex_init(&backgroundMutex, NULL);
    pthread_mutex_init(&audioBufferMutex, NULL);
    pthread_mutex_init(&fileDestinationMutex, NULL);
    pthread_mutex_init(&simulationRunningMutex, NULL);
    pthread_mutex_init(&backgroundDeviceCheckMutex, NULL);

    /* Buffers (N-API array buffers on desktop) */

    audioBuffer = calloc(AUDIO_BUFFER_SIZE, NUMBER_OF_BYTES_IN_SAMPLE);

    stftBuffer = calloc(AUDIO_BUFFER_SIZE / STFT_INPUT_OUTPUT_RATIO, NUMBER_OF_BYTES_IN_FLOAT32);

    if (audioBuffer == NULL || stftBuffer == NULL) {
        puts("[BACKSTAGE] Could not allocate audio buffers");
        return JNI_FALSE;
    }

    /* Start the background thread */

    pthread_create(&backgroundThread, NULL, backgroundThreadBody, NULL);

    /* Start device */

    pthread_mutex_lock(&stopStartMutex);
    started = false;
    pthread_mutex_unlock(&stopStartMutex);

    pthread_mutex_lock(&backgroundDeviceCheckMutex);

    device_check_t device_check = checkForAudioMoth(&deviceCheckContext, true);

    usingAudioMoth = device_check.audioMothFound;

    bool startedMicrophone = startMicrophone(&deviceCheckContext, usingAudioMoth);

    pthread_mutex_unlock(&backgroundDeviceCheckMutex);

    timeDeviceStarted = ma_timer_get_time_in_seconds(&timer);

    puts(usingAudioMoth ? "[BACKSTAGE] Started AudioMoth" : "[BACKSTAGE] Started default device");

    if (startedMicrophone) {
        if (!waitForStarted()) puts("[BACKSTAGE] Timed out waiting for device to start");
    } else {
        puts("[BACKSTAGE] Failed to start capture");
    }

    initialised = true;

    /* Desktop also fails here if capture didn't start; on Android the UI
       still runs (e.g. microphone permission pending) and getFrame retries. */

    return success ? JNI_TRUE : JNI_FALSE;

}

JNIEXPORT jobject JNICALL JNI_FN(nativeGetAudioBuffer)(JNIEnv *env, jclass clazz) {

    (void)clazz;

    return (*env)->NewDirectByteBuffer(env, audioBuffer, (jlong)AUDIO_BUFFER_SIZE * NUMBER_OF_BYTES_IN_SAMPLE);

}

JNIEXPORT jobject JNICALL JNI_FN(nativeGetStftBuffer)(JNIEnv *env, jclass clazz) {

    (void)clazz;

    return (*env)->NewDirectByteBuffer(env, stftBuffer, (jlong)AUDIO_BUFFER_SIZE / STFT_INPUT_OUTPUT_RATIO * NUMBER_OF_BYTES_IN_FLOAT32);

}

/* Device announcements from Kotlin */

JNIEXPORT void JNICALL JNI_FN(nativeSetAudioMothDevice)(JNIEnv *env, jclass clazz, jint fd, jint endpoint, jint maxPacketSize, jint sampleRate) {

    (void)env; (void)clazz;

    printf("[BACKSTAGE] setAudioMothDevice - fd %d, ep 0x%02x, mps %d, %d Hz", fd, endpoint, maxPacketSize, sampleRate);

    backstage_setAudioMothDevice(fd, endpoint, maxPacketSize, sampleRate);

}

JNIEXPORT void JNICALL JNI_FN(nativeClearAudioMothDevice)(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    puts("[BACKSTAGE] clearAudioMothDevice");

    backstage_clearAudioMothDevice();

}

/* changeSampleRate() */

JNIEXPORT void JNICALL JNI_FN(nativeChangeSampleRate)(JNIEnv *env, jclass clazz, jint number) {

    (void)env; (void)clazz;

    printf("[BACKSTAGE] changeSampleRate - %d\n", number);

    for (uint32_t i = 0; i < NUMBER_OF_VALID_SAMPLE_RATES; i += 1) {

        if (number == validSampleRates[i] && number != requestedSampleRate) {

            requestedSampleRate = number;

            requestedSampleRateChanged = true;

            break;

        }

    }

}

/* getFrame() */

JNIEXPORT void JNICALL JNI_FN(nativeGetFrame)(JNIEnv *env, jclass clazz, jlongArray out) {

    (void)clazz;

    jlong frame[FRAME_LENGTH];

    memset(frame, 0, sizeof(frame));

    /* Determine the offset and length for update and all data */

    pthread_mutex_lock(&audioBufferMutex);

    int32_t audioIndex = frontEndPaused ? captureBufferWriteIndex : audioBufferWriteIndex;

    int64_t audioCount = frontEndPaused ? captureBufferSampleCount : audioBufferSampleCount;

    int64_t unpausedAudioCount = audioBufferSampleCount;

    int64_t audioTime = audioBufferStartTime;

    pthread_mutex_unlock(&audioBufferMutex);

    /* Calculate unpaused audio time */

    int64_t unpausedAudioTime = audioTime + ROUNDED_DIV(unpausedAudioCount * MILLISECONDS_IN_SECOND, currentSampleRate);

    /* Apply time corrections */

    pthread_mutex_lock(&localTimeMutex);

    int64_t localTimeOffset = useLocalTime ? Time_getLocalTimeOffset() : 0;

    pthread_mutex_unlock(&localTimeMutex);

    audioTime += ROUNDED_DIV(audioCount * MILLISECONDS_IN_SECOND, currentSampleRate);

    audioTime += localTimeOffset * MILLISECONDS_IN_SECOND;

    /* Read the simulation state */

    pthread_mutex_lock(&simulationRunningMutex);

    bool simulationFlag = simulationRunning;

    pthread_mutex_unlock(&simulationRunningMutex);

    /* Check the audio time against the current time */

    int64_t currentTime = Time_getMillisecondUTC();

    bool timeMismatch = simulationFlag == false && ABS(currentTime - unpausedAudioTime) > TIME_MISMATCH_LIMIT;

    /* Check if the device has changed */

    bool deviceChanged = false;

    bool setOldAudioMothFoundFlag = false;

    static bool oldAudioMothFound = false;

    pthread_mutex_lock(&backgroundMutex);

    if (backgroundDeviceCheckTime - timeDeviceStarted > DEVICE_CHANGE_INTERVAL) {

        if (backgroundDeviceCheckFoundAudioMoth == true && usingAudioMoth == false) deviceChanged = true;

        if (backgroundDeviceCheckFoundAudioMoth == false && usingAudioMoth == true) deviceChanged = true;

        if (backgroundDeviceCheckFoundOldAudioMoth && oldAudioMothFound == false) setOldAudioMothFoundFlag = true;

        oldAudioMothFound = backgroundDeviceCheckFoundOldAudioMoth;

    }

    pthread_mutex_unlock(&backgroundMutex);

    /* Check if sample rate has changed */

    bool sampleRateChanged = false;

    if (requestedSampleRateChanged) {

        sampleRateChanged = currentSampleRate != MIN(requestedSampleRate, inputDeviceSampleRate);

        requestedSampleRateChanged = false;

    }

    /* Generate return values */

    frame[FRAME_REDRAW_REQUIRED] = !frontEndPaused && shouldSetRedrawFlag;
    frame[FRAME_SIMULATION_RUNNING] = simulationFlag;
    frame[FRAME_OLD_AUDIOMOTH_FOUND] = setOldAudioMothFoundFlag;
    frame[FRAME_MAXIMUM_SAMPLE_RATE] = usingAudioMoth ? audioMothSampleRate : maximumDefaultSampleRate;
    frame[FRAME_CURRENT_SAMPLE_RATE] = currentSampleRate;
    frame[FRAME_AUDIO_TIME] = audioTime;
    frame[FRAME_AUDIO_INDEX] = audioIndex;
    frame[FRAME_AUDIO_COUNT] = audioCount;
    frame[FRAME_USING_AUDIOMOTH] = usingAudioMoth;

    /* Android: polled replacements for the N-API callbacks */

    frame[FRAME_CAPTURE_RESULT] = captureResult;
    if (captureResult != 0) captureResult = 0;

    frame[FRAME_AUTOSAVE_ERROR] = autosaveErrorFlag;
    autosaveErrorFlag = false;

    pthread_mutex_lock(&writtenFileMutex);
    frame[FRAME_WRITTEN_FILE_COUNTER] = writtenFileCounter;
    pthread_mutex_unlock(&writtenFileMutex);

    /* Actions to take this frame */

    bool shouldRestart = false;

    bool shouldStopDevice = false;

    bool shouldStartDevice = false;

    bool shouldStopSimulationThread = false;

    bool shouldStartSimulationThread = false;

    if (!frontEndPaused) shouldSetRedrawFlag = false;

    /* Determine actions to take */

    if (deviceChanged && simulationFlag == false) {

        shouldStopDevice = true;

        shouldRestart = true;

        shouldStartDevice = true;

        deviceChanged = false;

    } else if (timeMismatch || sampleRateChanged) {

        if (timeMismatch) puts("[BACKSTAGE] Restarting due to time mismatch");

        if (simulationFlag == false) {

            shouldStopDevice = true;

        } else {

            shouldStopSimulationThread = true;

        }

        shouldRestart = true;

        if (simulationFlag == false) {

            shouldStartDevice = true;

        } else {

            shouldStartSimulationThread = true;

        }

    } else if (maximumDefaultSampleRateChanged) {

        bool sampleRateChanged = requestedMaximumDefaultSampleRate != maximumDefaultSampleRate;

        maximumDefaultSampleRate = requestedMaximumDefaultSampleRate;

        if (usingAudioMoth == false && simulationFlag == false && sampleRateChanged) {

            shouldStopDevice = true;

            shouldRestart = true;

            shouldStartDevice = true;

        }

        maximumDefaultSampleRateChanged = false;

    } else if (shouldStartSimulation) {

        if (simulationFlag == false) {

            shouldStopDevice = true;

        } else {

            shouldStopSimulationThread = true;

        }

        shouldRestart = true;

        shouldStartSimulationThread = true;

        shouldStartSimulation = false;

    } else if (shouldStopSimulation) {

        if (simulationFlag) {

            shouldStopSimulationThread = true;

            shouldRestart = true;

            shouldStartDevice = true;

        }

        shouldStopSimulation = false;

    }

    /* Implement the actions determined above */

    if (shouldStopDevice || shouldStopSimulationThread) {

        pthread_mutex_lock(&stopStartMutex);

        stopped = false;

        pthread_mutex_unlock(&stopStartMutex);

        if (shouldStopDevice) {

            puts(usingAudioMoth ? "[BACKSTAGE] Stop AudioMoth" : "[BACKSTAGE] Stop default device");

            pthread_mutex_lock(&backgroundDeviceCheckMutex);

            stopMicrophone();

            pthread_mutex_unlock(&backgroundDeviceCheckMutex);

        }

        if (shouldStopSimulationThread) {

            puts("[BACKSTAGE] Stop simulation thread");

            pthread_mutex_lock(&simulationRunningMutex);

            simulationRunning = false;

            pthread_mutex_unlock(&simulationRunningMutex);

        }

        /* Wait for device to stop */

        bool threadStopped = false;

        double startTime = ma_timer_get_time_in_seconds(&timer);

        while (threadStopped == false) {

            if (ma_timer_get_time_in_seconds(&timer) - startTime > DEVICE_STOP_START_TIMEOUT) {

                if (shouldStopDevice) puts("[BACKSTAGE] Timed out waiting for device to stop");

                if (shouldStopSimulationThread) puts("[BACKSTAGE] Timed out waiting for simulation thread to stop");

                break;

            }

            pthread_mutex_lock(&stopStartMutex);

            threadStopped = stopped;

            pthread_mutex_unlock(&stopStartMutex);

        }

        if (shouldStopSimulationThread) pthread_join(simulationThread, NULL);

    }

    if (shouldRestart) {

        pthread_mutex_lock(&audioBufferMutex);

        audioBufferSampleCount = 0;

        audioBufferIndex -= audioBufferIndex % STFT_INPUT_SAMPLES;

        pthread_mutex_unlock(&audioBufferMutex);

        pthread_mutex_lock(&playbackMutex);

        playbackBufferCount = 0;

        playbackReadIndex = audioBufferWriteIndex;

        pthread_mutex_unlock(&playbackMutex);

        shouldSetRedrawFlag = true;

    }

    if (shouldStartDevice || shouldStartSimulationThread) {

        pthread_mutex_lock(&stopStartMutex);

        started = false;

        pthread_mutex_unlock(&stopStartMutex);

        bool startRequested = true;

        if (shouldStartDevice) {

            pthread_mutex_lock(&backgroundDeviceCheckMutex);

            device_check_t device_check = checkForAudioMoth(&deviceCheckContext, true);

            usingAudioMoth = device_check.audioMothFound;

            startRequested = startMicrophone(&deviceCheckContext, usingAudioMoth);

            pthread_mutex_unlock(&backgroundDeviceCheckMutex);

            puts(usingAudioMoth ? "[BACKSTAGE] Start AudioMoth" : "[BACKSTAGE] Start default device");

            timeDeviceStarted = ma_timer_get_time_in_seconds(&timer);

        }

        if (shouldStartSimulationThread) {

            Simulator_initialiseExample();

            inputDeviceSampleRate = Simulator_getSampleRate(simulationIndex);

            strncpy(inputDeviceName, "Simulated 384kHz AudioMoth USB Microphone", DEVICE_NAME_SIZE);

            strncpy(inputDeviceCommentName, "a simulated 384kHz AudioMoth USB Microphone", DEVICE_NAME_SIZE);

            currentSampleRate = inputDeviceSampleRate;

            puts("[BACKSTAGE] Start simulation thread");

            pthread_mutex_lock(&simulationRunningMutex);

            simulationRunning = true;

            pthread_mutex_unlock(&simulationRunningMutex);

            pthread_create(&simulationThread, NULL, simulationThreadBody, NULL);

        }

        /* Wait for device to start (Android: not if it failed to start, to keep the UI responsive) */

        bool threadStarted = startRequested && waitForStarted();

        if (!threadStarted) puts("[BACKSTAGE] Device did not start");

        /* Add autosave event */

        pthread_mutex_lock(&autosaveMutex);

        int32_t currentDuration = autosaveDuration;

        pthread_mutex_unlock(&autosaveMutex);

        if (threadStarted && currentDuration > 0) addAutosaveEvent(AS_RESTART);

    }

    (*env)->SetLongArrayRegion(env, out, 0, FRAME_LENGTH, frame);

}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetDeviceName)(JNIEnv *env, jclass clazz) {

    (void)clazz;

    return (*env)->NewStringUTF(env, inputDeviceName);

}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetCaptureFilename)(JNIEnv *env, jclass clazz) {

    (void)clazz;

    return (*env)->NewStringUTF(env, captureResultFilename);

}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetLastWrittenFilename)(JNIEnv *env, jclass clazz) {

    (void)clazz;

    static char buffer[FILENAME_SIZE];

    pthread_mutex_lock(&writtenFileMutex);

    strncpy(buffer, lastWrittenFilename, FILENAME_SIZE - 1);

    pthread_mutex_unlock(&writtenFileMutex);

    return (*env)->NewStringUTF(env, buffer);

}

/* clear() */

JNIEXPORT void JNICALL JNI_FN(nativeClear)(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    puts("[BACKSTAGE] clear");

    pthread_mutex_lock(&audioBufferMutex);

    int64_t milliseconds = ROUNDED_DIV(audioBufferSampleCount * MILLISECONDS_IN_SECOND, (int64_t)currentSampleRate);

    audioBufferStartTime += milliseconds;

    audioBufferSampleCount = 0;

    pthread_mutex_unlock(&audioBufferMutex);

    shouldSetRedrawFlag = true;

}

/* capture() - result is reported through FRAME_CAPTURE_RESULT */

JNIEXPORT void JNICALL JNI_FN(nativeCapture)(JNIEnv *env, jclass clazz, jint duration) {

    (void)env; (void)clazz;

    printf("[BACKSTAGE] capture - %d\n", duration);

    captureResult = 0;

    if (!frontEndPaused) {

        duration = MAX(0, MIN(MAXIMUM_RECORD_DURATION, duration));

        captureAudioBuffer(duration);

    }

    pthread_create(&captureBufferThread, NULL, captureBufferThreadBody, NULL);

    pthread_detach(captureBufferThread);

}

/* setPause() - returns true when paused; out = [audioStartTime, audioIndex, audioCount] */

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPause)(JNIEnv *env, jclass clazz, jboolean enable, jint duration, jlongArray out) {

    (void)clazz;

    printf("[BACKSTAGE] setPause - %s, %d\n", enable ? "true" : "false", duration);

    if (enable && !frontEndPaused) {

        duration = MAX(0, MIN(MAXIMUM_RECORD_DURATION, duration));

        captureAudioBuffer(duration);

        frontEndPaused = true;

    }

    if (!enable && frontEndPaused) {

        frontEndPaused = false;

        shouldSetRedrawFlag = true;

    }

    if (frontEndPaused) {

        jlong values[3] = {captureBufferStartTime, captureBufferWriteIndex, captureBufferSampleCount};

        (*env)->SetLongArrayRegion(env, out, 0, 3, values);

    }

    return frontEndPaused ? JNI_TRUE : JNI_FALSE;

}

/* setFileDestination() */

JNIEXPORT void JNICALL JNI_FN(nativeSetFileDestination)(JNIEnv *env, jclass clazz, jstring destination) {

    (void)clazz;

    const char *path = (*env)->GetStringUTFChars(env, destination, NULL);

    printf("[BACKSTAGE] setFileDestination - %s\n", path);

    pthread_mutex_lock(&fileDestinationMutex);

    strncpy(fileDestination, path, FILEPATH_SIZE - 1);

    fileDestinationSet = true;

    pthread_mutex_unlock(&fileDestinationMutex);

    (*env)->ReleaseStringUTFChars(env, destination, path);

}

/* setAutoSave() */

JNIEXPORT void JNICALL JNI_FN(nativeSetAutoSave)(JNIEnv *env, jclass clazz, jint duration) {

    (void)env; (void)clazz;

    printf("[BACKSTAGE] setAutoSave - %d\n", duration);

    pthread_mutex_lock(&autosaveMutex);

    int32_t currentDuration = autosaveDuration;

    pthread_mutex_unlock(&autosaveMutex);

    if (currentDuration == 0 && duration > 0) addAutosaveEvent(AS_START);

    if (currentDuration > 0 && duration == 0) addAutosaveEvent(AS_STOP);

    if (currentDuration != duration) {

        pthread_mutex_lock(&autosaveMutex);

        autosaveDuration = duration;

        pthread_mutex_unlock(&autosaveMutex);

    }

}

/* getSimulationInfo() / setSimulation() */

JNIEXPORT jstring JNICALL JNI_FN(nativeSetSimulationPath)(JNIEnv *env, jclass clazz, jstring path) {

    (void)clazz;

    static char buffer[FILEPATH_SIZE];

    const char *p = (*env)->GetStringUTFChars(env, path, NULL);

    strncpy(buffer, p, FILEPATH_SIZE - 1);

    (*env)->ReleaseStringUTFChars(env, path, p);

    printf("[BACKSTAGE] getSimulationInfo - %s\n", buffer);

    Simulator_setPath(buffer);

    /* NUMBER_OF_SIMULATION_EXAMPLES is 1: return its description */

    return (*env)->NewStringUTF(env, Simulator_getDescription(0));

}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetSimulation)(JNIEnv *env, jclass clazz, jboolean enable, jint index) {

    (void)env; (void)clazz;

    bool success = true;

    if (enable) {

        printf("[BACKSTAGE] setSimulation - true, %d\n", index);

        success = Simulator_loadExample(index);

        if (success) {

            shouldStartSimulation = true;

            simulationIndex = index;

        }

    } else {

        puts("[BACKSTAGE] setSimulation - false");

        shouldStopSimulation = true;

    }

    return success ? JNI_TRUE : JNI_FALSE;

}

/* setMonitor() */

JNIEXPORT void JNICALL JNI_FN(nativeSetMonitor)(JNIEnv *env, jclass clazz, jint mode, jint frequency) {

    (void)env; (void)clazz;

    printf("[BACKSTAGE] setMonitor - %d, %d\n", mode, frequency);

    if (mode == MONITOR_HETERODYNE) Heterodyne_updateFrequencies(MAXIMUM_SAMPLE_RATE, frequency);

    heterodyneEnabled = mode == MONITOR_HETERODYNE;

    if (!monitorEnabled && (mode == MONITOR_PLAYTHROUGH || mode == MONITOR_HETERODYNE)) {

        playbackBufferCount = 0;

        playbackReadIndex = audioBufferWriteIndex;

        pthread_create(&startPlaybackThread, NULL, startPlaybackThreadBody, NULL);

        pthread_detach(startPlaybackThread);

        monitorEnabled = true;

    } else if (monitorEnabled && mode == MONITOR_OFF) {

        pthread_create(&stopPlaybackThread, NULL, stopPlaybackThreadBody, NULL);

        pthread_detach(stopPlaybackThread);

        monitorEnabled = false;

    }

}

/* setHighDefaultSampleRate() / setLocalTime() */

JNIEXPORT void JNICALL JNI_FN(nativeSetHighDefaultSampleRate)(JNIEnv *env, jclass clazz, jboolean enable) {

    (void)env; (void)clazz;

    requestedMaximumDefaultSampleRate = enable ? MAXIMUM_SAMPLE_RATE : DEFAULT_SAMPLE_RATE;

    maximumDefaultSampleRateChanged = true;

}

JNIEXPORT void JNICALL JNI_FN(nativeSetLocalTime)(JNIEnv *env, jclass clazz, jboolean enable) {

    (void)env; (void)clazz;

    printf("[BACKSTAGE] setLocalTime - %s\n", enable ? "true" : "false");

    pthread_mutex_lock(&localTimeMutex);

    useLocalTime = enable;

    pthread_mutex_unlock(&localTimeMutex);

}

/* forceAutoSaveToStop() */

JNIEXPORT void JNICALL JNI_FN(nativeForceAutoSaveToStop)(JNIEnv *env, jclass clazz) {

    (void)env; (void)clazz;

    puts("[BACKSTAGE] forceAutoSaveToStop");

    pthread_mutex_lock(&autosaveMutex);

    autosaveShutdownCompleted = false;

    pthread_mutex_unlock(&autosaveMutex);

    addAutosaveEvent(AS_SHUTDOWN);

    bool shutdownCompleted = false;

    double startTime = ma_timer_get_time_in_seconds(&timer);

    while (shutdownCompleted == false) {

        if (ma_timer_get_time_in_seconds(&timer) - startTime > DEVICE_SHUTDOWN_TIMEOUT) {

            puts("[BACKSTAGE] Timed out waiting for shutdown to complete");

            break;

        }

        pthread_mutex_lock(&autosaveMutex);

        shutdownCompleted = autosaveShutdownCompleted;

        pthread_mutex_unlock(&autosaveMutex);

        usleep(1000);

    }

    puts("[BACKSTAGE] Autosave shutdown complete");

}
