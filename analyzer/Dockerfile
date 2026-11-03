# The audio analyzer: listens to every song once and saves what it sounds like.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
# CPU-only PyTorch (the index has ARM and x86 builds).
RUN pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt
# For learning labels (analyze.py learn()). librosa already brings it; a step of its own, so adding things here
# never makes Docker download PyTorch again.
RUN pip install --no-cache-dir --default-timeout=120 --retries 10 "scikit-learn>=1.3"
COPY analyze.py .
# The AI model is downloaded once into /data/models and reused.
ENV HF_HOME=/data/models PYTHONUNBUFFERED=1 NUMBA_CACHE_DIR=/tmp/numba
ENTRYPOINT ["python", "analyze.py"]
CMD ["watch"]
