cmake_minimum_required(VERSION 3.22.1)
project(audiomothlive C)

# Desktop sources (unchanged)
set(BACKSTAGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../backstage)

# Phase 0 proof-of-concept capture (used by the USB diagnostics screen)
add_library(usbcapture SHARED usb_capture.c)
target_compile_options(usbcapture PRIVATE -Wall -Wextra -O2)
target_link_libraries(usbcapture log)

# Backstage engine: generated/backstage_core.c (from backstage.c, see
# android/tools/port_backstage.py) + android_device.c + JNI, and the plotter
add_library(backstage SHARED
    backstage_jni.c
    plotter.c
    ${BACKSTAGE_DIR}/src/stft.c
    ${BACKSTAGE_DIR}/src/xtime.c
    ${BACKSTAGE_DIR}/src/biquad.c
    ${BACKSTAGE_DIR}/src/wavFile.c
    ${BACKSTAGE_DIR}/src/autosave.c
    ${BACKSTAGE_DIR}/src/simulator.c
    ${BACKSTAGE_DIR}/src/heterodyne.c
)
target_include_directories(backstage PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${BACKSTAGE_DIR}/inc
    ${BACKSTAGE_DIR}/miniaudio
)
target_compile_options(backstage PRIVATE -O3 -Wall -Wno-unused-parameter -Wno-unused-function -Wno-unused-variable)
target_link_libraries(backstage log jnigraphics m)
