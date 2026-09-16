cmake_minimum_required(VERSION 3.22.1)

project("llama-android")

# Build llama.cpp (vendored in-tree). Disable everything we don't ship so the
# Android cross-compile stays lean: just core llama + ggml and the CPU backend.
#
# Those land as separate .so files (libggml, libggml-base, libggml-cpu, libllama)
# which our libllama-android.so pulls in through ordinary DT_NEEDED links, so the
# dynamic linker loads the whole chain on System.loadLibrary. That is NOT the same
# as GGML_BACKEND_DL: there the backends become MODULE libraries with no NEEDED
# entry, discovered only by a runtime directory scan — which is what broke on
# device (see "Target CPU ISA" below and docs/ARCHITECTURE.md).
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
# Deliberately left at the NDK's baseline armv8-a. ggml's accelerated integer
# kernels are guarded on __ARM_FEATURE_DOTPROD / __ARM_FEATURE_MATMUL_INT8, which
# the compiler only defines from -march, so this build does NOT get them — that
# is a known cost, accepted for a reason.
#
# v1.11 briefly set this to "armv8.2-a+dotprod+fp16". The kernels were verified
# present (1048 sdot/udot in libggml-cpu.so) but the maintainer's own phone — a
# Xiaomi on Android 16 with 8 GB of RAM — could not run on-device models at all
# afterwards, and both detection paths (/proc/cpuinfo's Features line, then
# AT_HWCAP) agreed the CPU has no asimddp.
#
# The lesson worth keeping: **RAM size says nothing about CPU generation, and
# dot product is OPTIONAL in ARMv8.2 (mandatory only from ARMv8.4).** Budget
# phones routinely pair 8 GB of RAM with Cortex-A73/A53 (ARMv8.0, no dotprod) or
# an A55 whose FEAT_DotProd was not implemented. For an on-device LLM app whose
# natural audience is exactly "cheap phone with lots of RAM", a fixed armv8.2
# baseline excludes a large part of the market — including this project's primary
# test device. A build that is faster on hardware nobody here owns is worth less
# than a build that runs.
#
# Getting those kernels back without dropping any device needs runtime dispatch
# per CPU tier, not a raised compile-time baseline. GGML_CPU_ALL_VARIANTS is the
# supported route and was also tried and reverted (it requires GGML_BACKEND_DL,
# whose registry scans a filesystem directory that Android never populates —
# see docs/ARCHITECTURE.md for the full autopsy and the bare-soname path that
# would sidestep it).

# --- GPU acceleration (Vulkan + OpenCL) -------------------------------------
# Off by default so the plain CPU cross-compile always works (e.g. contributors
# without the GPU SDKs). CI turns this on with -DORACLE_GPU=ON and supplies the
# OpenCL loader (OpenCL_INCLUDE_DIR / OpenCL_LIBRARY) and a Vulkan glslc on PATH.
option(ORACLE_GPU "Build llama.cpp with GPU backends (Vulkan + OpenCL)" OFF)

if(ORACLE_GPU)
    # The GPU backends are DT_NEEDED dependencies of libggml.so just like the CPU
    # backend, so they load with it and self-register at init — no runtime plugin
    # discovery anywhere in this build. Vulkan is safe down to minSdk because
    # ggml-vulkan resolves its one >1.0 entry point dynamically (see the
    # ggml-vulkan.cpp patch); OpenCL binds the device's ICD at runtime.

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
