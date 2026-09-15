cmake_minimum_required(VERSION 3.22.1)
project(pitchshifter LANGUAGES C CXX)

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus EXCLUDE_FROM_ALL)

add_library(pitchshifter SHARED
    pitch_shifter_jni.cpp
    software_opus_jni.cpp
    rnnoise_jni.cpp
    rnnoise/src/denoise.c
    rnnoise/src/rnn.c
    rnnoise/src/pitch.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/rnnoise_tables.c
    rnnoise/src/rnnoise_data.c
    rnnoise/src/nnet.c
    rnnoise/src/nnet_default.c
    rnnoise/src/parse_lpcnet_weights.c
)

set_target_properties(pitchshifter PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(pitchshifter PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/signalsmith-stretch
    ${CMAKE_CURRENT_SOURCE_DIR}/opus/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src
)

# Signalsmith Stretch is substantially slower without optimisation.
target_compile_options(pitchshifter PRIVATE -O2)

find_library(log-lib log)
target_link_libraries(pitchshifter opus ${log-lib})
