#!/bin/sh
set -eu
if [ ! -f "$DOCLING_ARTIFACTS_PATH/.ready" ]; then
    python /app/prefetch.py
fi
exec uvicorn server:app --host 0.0.0.0 --port 8741
