cmake_minimum_required(VERSION 3.22.1)

project("llama-android")

# Build llama.cpp (vendored in-tree). Disable everything we don't ship so the
# Android cross-compile stays lean: just the core llama + ggml, plus the CPU
# backend variants selected at runtime (see "Target CPU ISA" below).
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
# CPU (an x86_64 runner) rather than the target. Target ISA features come from
# GGML_CPU_ALL_VARIANTS below instead.
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)

# --- Target CPU ISA (runtime dispatch) --------------------------------------
# Left alone, the cross-compile lands on the NDK's baseline armv8-a and none of
# ggml's accelerated integer kernels make it into the binary at all: they are
# guarded on __ARM_FEATURE_DOTPROD / __ARM_FEATURE_MATMUL_INT8, which the compiler
# only defines from -march. Since quantized matmul is most of both prefill and
# generation, that is the single largest CPU win on the table (it also switches on
# the equivalent paths inside the llamafile sgemm kernels enabled above).
#
# Rather than commit the whole library to one baseline — which would trade away
# every device below it — ALL_VARIANTS compiles the CPU backend once per feature
# tier and picks the best one the CPU actually supports at runtime. ggml ships an
# Android-specific tier list (android_armv8.0_1 through android_armv9.2_2), so
# ARMv8.0 parts keep working on the baseline variant while newer silicon gets
# dotprod, i8mm, SVE2 or SME.
#
# The cost is packaging: ALL_VARIANTS requires GGML_BACKEND_DL, which in turn
# requires BUILD_SHARED_LIBS. So instead of one statically-linked
# libllama-android.so the APK carries libllama/libggml/libggml-base plus one
# libggml-cpu-<tier>.so per variant, each a full copy of the kernels. They are
# dlopen-ed at startup by ggml's backend registry — see the
# ggml_backend_load_all_from_path call in llama-android.cpp, which must point at
# the app's nativeLibraryDir because the registry's default search paths (the
# executable dir and the process CWD) find nothing on Android.
set(BUILD_SHARED_LIBS        ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS    ON CACHE BOOL "" FORCE)

# --- GPU acceleration (Vulkan + OpenCL) -------------------------------------
# Off by default so the plain CPU cross-compile always works (e.g. contributors
# without the GPU SDKs). CI turns this on with -DORACLE_GPU=ON and supplies the
# OpenCL loader (OpenCL_INCLUDE_DIR / OpenCL_LIBRARY) and a Vulkan glslc on PATH.
option(ORACLE_GPU "Build llama.cpp with GPU backends (Vulkan + OpenCL)" OFF)

if(ORACLE_GPU)
    # Under GGML_BACKEND_DL (above) the GPU backends are their own dlopen-able
    # .so files rather than being statically linked in, so they are loaded by the
    # same ggml_backend_load_all_from_path call that brings in the CPU variants. A
    # device with no usable driver simply fails that dlopen, which DL mode handles
    # gracefully, and the CPU backend still registers — so GPU is additive, never
    # load-bearing. Vulkan is safe down to minSdk because ggml-vulkan resolves its
    # one >1.0 entry point dynamically (see the ggml-vulkan.cpp patch); OpenCL
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
