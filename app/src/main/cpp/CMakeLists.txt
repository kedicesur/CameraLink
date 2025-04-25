cmake_minimum_required(VERSION 3.10)

# Use the Android NDK's CMake toolchain
set(CMAKE_TOOLCHAIN_FILE $ENV{ANDROID_NDK}/build/cmake/android.toolchain.cmake)
set(ANDROID_ABI arm64-v8a)
set(ANDROID_PLATFORM android-21)
set(CMAKE_ANDROID_STL_TYPE c++_shared)

project(srt_android LANGUAGES C CXX)

# -- Import prebuilt libraries --
# SRT
add_library(srt SHARED IMPORTED)
set_target_properties(srt PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsrt.so
)

# FFmpeg components
foreach(lib avcodec avformat avutil swresample swscale)
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES
            IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/lib${lib}.so
    )
endforeach()

# -- Define wrapper libraries --
add_library(srt_wrapper SHARED srt_wrapper.cpp)
add_library(ffmpeg_wrapper SHARED ffmpeg_wrapper.cpp)

# -- Include directories for your headers --
target_include_directories(srt_wrapper PRIVATE
        ${CMAKE_SOURCE_DIR}/include/srt
)
target_include_directories(ffmpeg_wrapper PRIVATE
        ${CMAKE_SOURCE_DIR}/include/ffmpeg
)

# -- Find Android system libraries --
find_library(log-lib log)

# -- Link libraries --
# srt_wrapper
target_link_libraries(srt_wrapper
        PRIVATE
        srt
        ${log-lib}
        android
        c++_shared
)

# ffmpeg_wrapper
target_link_libraries(ffmpeg_wrapper
        PRIVATE
        avcodec
        avformat
        avutil
        swresample
        swscale
        ${log-lib}
        android
        c++_shared
)
