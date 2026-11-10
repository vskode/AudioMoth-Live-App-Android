cmake_minimum_required(VERSION 3.22.1)
project(audiomothlive C)

# Shared C sources from the desktop app (unchanged)
set(BACKSTAGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../backstage)

# Phase 0 proof-of-concept capture (used by the diagnostics screen)
add_library(usbcapture SHARED usb_capture.c)
target_compile_options(usbcapture PRIVATE -Wall -Wextra -O2)
target_link_libraries(usbcapture log)

# Live engine: USB streaming + resampler + STFT + plotter
add_library(engine SHARED
    engine.c
    plotter.c
    ${BACKSTAGE_DIR}/src/stft.c
)
target_include_directories(engine PRIVATE ${BACKSTAGE_DIR}/inc)
target_compile_options(engine PRIVATE -Wall -Wextra -O3)
target_link_libraries(engine log jnigraphics m)
