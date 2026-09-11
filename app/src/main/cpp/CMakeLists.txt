cmake_minimum_required(VERSION 3.22.1)
project(pitchshifter LANGUAGES CXX)

add_library(pitchshifter SHARED pitch_shifter_jni.cpp)

set_target_properties(pitchshifter PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(pitchshifter PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/signalsmith-stretch
)

# Signalsmith Stretch is substantially slower without optimisation.
target_compile_options(pitchshifter PRIVATE -O2)

find_library(log-lib log)
target_link_libraries(pitchshifter ${log-lib})
