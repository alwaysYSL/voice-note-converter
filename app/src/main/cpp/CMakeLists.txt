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
)

set_target_properties(pitchshifter PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(pitchshifter PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/signalsmith-stretch
    ${CMAKE_CURRENT_SOURCE_DIR}/opus/include
)

# Signalsmith Stretch is substantially slower without optimisation.
target_compile_options(pitchshifter PRIVATE -O2)

find_library(log-lib log)
target_link_libraries(pitchshifter opus ${log-lib})
