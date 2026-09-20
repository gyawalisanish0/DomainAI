cmake_minimum_required(VERSION 3.22.1)

project("llama-android")

# Build llama.cpp (vendored in-tree). Disable everything we don't ship so the
# Android cross-compile stays lean: just core llama + ggml and the CPU backend.
#
# libllama and libggml link through ordinary DT_NEEDED, but the *backends* do not:
# GGML_BACKEND_DL (see "Target CPU ISA" below) makes each one a MODULE library with
# no NEEDED entry, loaded explicitly at runtime. Upstream finds them by scanning a
# directory, which on Android finds nothing — the .so files live uncompressed inside
# the APK and are never written to nativeLibraryDir. We name them instead; see
# LLamaAndroid.configure and CpuVariant.
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

# --- Target CPU ISA: one build per tier, chosen at runtime -------------------
# ggml's accelerated integer kernels are guarded on __ARM_FEATURE_DOTPROD /
# __ARM_FEATURE_MATMUL_INT8, which the compiler only defines from -march. A single
# binary therefore has to choose: name a high baseline and drop every older device,
# or name none and leave the kernels out for everyone.
#
# v1.11 tried the first and it was wrong — "armv8.2-a+dotprod+fp16" built fine and
# then could not run on this project's own test device, because **dot product is
# OPTIONAL in ARMv8.2 and mandatory only from ARMv8.4**. Budget parts pair plenty of
# RAM with a core that lacks it.
#
# GGML_CPU_ALL_VARIANTS is the way out: compile the CPU backend once per feature
# tier (android_armv8.0_1 … android_armv9.2_2) and select at runtime, so every
# device gets the best kernels it can actually execute and none are excluded.
set(GGML_BACKEND_DL        ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON CACHE BOOL "" FORCE)
# Required by GGML_BACKEND_DL (ggml enforces this with a FATAL_ERROR).
set(BUILD_SHARED_LIBS      ON CACHE BOOL "" FORCE)

# Under GGML_BACKEND_DL every backend becomes a CMake MODULE library, and ggml
# sends MODULE output to CMAKE_RUNTIME_OUTPUT_DIRECTORY rather than the library
# directory. AGP collects native libraries from the library directory, so without
# this the variant .so files can build correctly and never reach the APK — which
# looks identical, at runtime, to the backends failing to load. Point both at the
# same place so every artifact lands where the packager looks.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

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
