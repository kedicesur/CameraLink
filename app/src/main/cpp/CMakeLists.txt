cmake_minimum_required(VERSION 3.10)
project(srt_android)

add_library(srt SHARED IMPORTED)
set_target_properties(srt PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libsrt.so)

include_directories(${CMAKE_SOURCE_DIR}/include)

add_library(srt_wrapper SHARED srt_wrapper.cpp)
target_link_libraries(srt_wrapper srt log)
