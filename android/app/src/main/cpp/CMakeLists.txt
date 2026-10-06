cmake_minimum_required(VERSION 3.22)
project(vsdecoder CXX)

# ncnn's prebuilt Android libraries with Vulkan (static), downloaded once at configure time and checked by hash
include(FetchContent)
FetchContent_Declare(ncnn_prebuilt
    URL https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip
    URL_HASH SHA256=26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804)
FetchContent_GetProperties(ncnn_prebuilt)
if(NOT ncnn_prebuilt_POPULATED)
    FetchContent_Populate(ncnn_prebuilt)
endif()
set(ncnn_DIR ${ncnn_prebuilt_SOURCE_DIR}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(vsdecoder SHARED ncnn_decoder.cpp)
target_link_libraries(vsdecoder ncnn android log)
