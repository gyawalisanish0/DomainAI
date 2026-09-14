cmake_minimum_required(VERSION 3.22.1)

project("llama-android")

# Build llama.cpp (vendored in-tree). Disable everything we don't ship so the
# Android cross-compile stays lean: just the core llama + ggml CPU backend,
# statically linked into our single JNI library.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# OpenMP stays off: ggml's own pthread threadpool is what lets us pin workers to
# the fastest cores (see the cpumask in llama-android.cpp), and it saves shipping
# libomp.so.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# llamafile/tinyBLAS sgemm kernels — upstream's default (GGML_LLAMAFILE_DEFAULT
# is ON), and the blocked matmul path that prompt prefill leans on. It was off
# only to keep the cross-compile lean, which traded prefill speed for a smaller
# .so; prefill latency is the more valuable of the two here.
set(GGML_LLAMAFILE       ON  CACHE BOOL "" FORCE)

# NATIVE must stay off when cross-compiling — it would probe the *build host's*
# CPU (an x86_64 runner) rather than the target. Target ISA features are selected
# by GGML_CPU_ARM_ARCH / GGML_CPU_ALL_VARIANTS instead.
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)

# --- Target CPU ISA ---------------------------------------------------------
# Without this the cross-compile lands on the NDK's baseline armv8-a, so none of
# ggml's accelerated integer kernels are compiled in at all: they are guarded on
# __ARM_FEATURE_DOTPROD, which the compiler only defines from -march. That makes
# dotprod the single largest CPU win available, since quantized matmul is most of
# both prefill and generation (it also switches on the dotprod paths inside the
# llamafile sgemm kernels enabled above).
#
# Requires an ARMv8.2 CPU. Every arm64 SoC from ~2017 on has dotprod
# (Cortex-A55/A75 and later: Snapdragon 845+, Exynos 9xxx, Dimensity, Tensor),
# but ARMv8.0 arm64 parts do not — Snapdragon 835 and Exynos 8895 most notably.
# Those instructions can be emitted anywhere in the library, so such a device
# would SIGILL at an arbitrary point rather than fail cleanly; DeviceCapabilities
# checks the dotprod hwcap before the native library is loaded and keeps the app
# on its offline responder instead.
#
# i8mm/SVE2/SME are deliberately NOT requested here: they would raise the
# baseline to armv8.6-a and drop everything before ~2021 flagships. Recovering
# those without dropping any device needs GGML_CPU_ALL_VARIANTS runtime dispatch,
# which is a separate change (it forces BUILD_SHARED_LIBS and multi-.so
# packaging).
set(GGML_CPU_ARM_ARCH    "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# --- GPU acceleration (Vulkan + OpenCL) -------------------------------------
# Off by default so the plain CPU cross-compile always works (e.g. contributors
# without the GPU SDKs). CI turns this on with -DORACLE_GPU=ON and supplies the
# OpenCL loader (OpenCL_INCLUDE_DIR / OpenCL_LIBRARY) and a Vulkan glslc on PATH.
option(ORACLE_GPU "Build llama.cpp with GPU backends (Vulkan + OpenCL)" OFF)

if(ORACLE_GPU)
    # GPU backends are statically linked into libllama-android.so and
    # self-register at init, so the CPU backend is always present (no runtime
    # plugin loading). Vulkan is safe down to minSdk because ggml-vulkan resolves
    # its one >1.0 entry point dynamically (see the ggml-vulkan.cpp patch); OpenCL
    # binds the device's ICD at runtime.

    # OpenCL: the Adreno-optimized backend Qualcomm contributed. Kernels are
    # embedded into the .so (no runtime asset loading) and the Adreno-tuned
    # kernels are selected — best path for Adreno-class GPUs.
    set(GGML_OPENCL                   ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS     ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)

    # Vulkan: portable GPU backend. Cross-compiling its shaders needs a host build
    # of vulkan-shaders-gen, so point ggml at a host toolchain file.
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
        "${CMAKE_CURRENT_LIST_DIR}/host-toolchain.cmake" CACHE STRING "" FORCE)
endif()

add_subdirectory(llama.cpp build-llama)

add_library(llama-android SHARED llama-android.cpp)

target_include_directories(llama-android PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/ggml/include)

find_library(log-lib log)

target_link_libraries(llama-android
    llama
    android
    ${log-lib})
