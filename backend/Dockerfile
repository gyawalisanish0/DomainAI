FROM python:3.11-slim

# Build tools needed to compile llama-cpp-python from source.
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake git && \
    rm -rf /var/lib/apt/lists/*

# Build llama-cpp-python with AVX2 / FMA / F16C for faster CPU prefill.
# Pass --build-arg CUDA=1 to also enable the CUDA backend (requires nvidia base image).
ARG CUDA=0
RUN CUDA_FLAGS=$( [ "$CUDA" = "1" ] && echo "-DGGML_CUDA=ON" || echo "" ) && \
    CMAKE_ARGS="-DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON $CUDA_FLAGS" \
    pip install --no-cache-dir "llama-cpp-python==0.3.4"

# HF Spaces runs as a non-root user; 1000:1000 is the standard UID/GID.
RUN useradd -m -u 1000 appuser

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Persistent model storage: HF Spaces mounts /data when persistent storage is
# enabled in Space settings. Pre-create the directory so the app starts cleanly
# even before persistent storage is mounted (files will be in the container layer).
RUN mkdir -p /data/models

COPY capabilities.py errors.py engine.py main.py models_catalog.py ./

# HF Spaces Docker spaces must expose port 7860.
EXPOSE 7860

USER appuser

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860"]
