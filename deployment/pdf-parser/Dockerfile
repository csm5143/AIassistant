FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY deployment/pdf-parser/requirements-lock.txt ./requirements-lock.txt
RUN --mount=type=cache,target=/root/.cache/pip pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements-lock.txt
COPY deployment/pdf-parser/server.py deployment/pdf-parser/text_cleanup.py deployment/pdf-parser/prefetch.py ./
COPY deployment/pdf-parser/entrypoint.sh /app/entrypoint.sh
ENV PDF_PARSER_RUNTIME=/runtime DOCLING_ARTIFACTS_PATH=/models PYTHONUNBUFFERED=1
RUN mkdir /runtime /models && chmod +x /app/entrypoint.sh
EXPOSE 8741
ENTRYPOINT ["/app/entrypoint.sh"]
