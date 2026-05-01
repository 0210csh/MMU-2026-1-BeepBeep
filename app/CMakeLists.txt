cmake_minimum_required(VERSION 3.22.1)
project("beepdefense")

add_library(resonance_bridge SHARED
        src/main/cpp/resonance_bridge.cpp)

target_compile_options(resonance_bridge PRIVATE -std=c++17)

target_include_directories(resonance_bridge PRIVATE
        src/main/cpp
        src/main/cpp/resonance)

add_library(ResonanceAudioShared SHARED IMPORTED)
set_target_properties(ResonanceAudioShared PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${CMAKE_ANDROID_ARCH_ABI}/libResonanceAudioShared.so)

target_link_libraries(resonance_bridge
        ResonanceAudioShared
        android
        log)
