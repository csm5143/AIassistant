"""Loopback-only Docling worker. Accepts PDF bytes, never paths or URLs.

Java selects difficult pages and restores their original page numbers. Local OCR
and layout extraction are cached; external vision is handled by the Java API.
"""
import base64
import hashlib
import hmac
import io
import json
import os
import tempfile
import threading
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from text_cleanup import NativeText, table_warning, restore_numeric_table

ROOT = Path(os.environ.get("PDF_PARSER_RUNTIME", "D:/AIassistant-env/runtime/pdf-parser"))
ROOT.mkdir(parents=True, exist_ok=True)
TMP = ROOT / "tmp"
TMP.mkdir(exist_ok=True)
os.environ["TEMP"] = os.environ["TMP"] = str(TMP)
tempfile.tempdir = str(TMP)
TOKEN = os.environ.get("PDF_PARSER_TOKEN") or (ROOT / "token").read_text().strip()
MODELS = Path(os.environ.get("DOCLING_ARTIFACTS_PATH", "D:/AIassistant-env/models/docling"))
CACHE = ROOT / "cache"
CACHE.mkdir(exist_ok=True)
VERSION = "docling-2.130.0-rapidocr-v5-native-table"
FAST_VERSION = "rapidocr-direct-v1-150dpi"
MAX_BYTES = 100 * 1024 * 1024
LOCK = threading.Lock()
CONVERTERS = {}
FAST_OCR = None
FAST_OCR_SECOND = None

from fastapi import FastAPI, HTTPException, Request
from starlette.concurrency import run_in_threadpool

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


def authorize(request):
    if not hmac.compare_digest(request.headers.get("X-Parser-Token", ""), TOKEN):
        raise HTTPException(401, "Unauthorized")


def converter(force):
    if force not in CONVERTERS:
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.pipeline_options import PdfPipelineOptions, RapidOcrOptions, OcrMode
        from docling.datamodel.accelerator_options import AcceleratorOptions, AcceleratorDevice
        from docling.document_converter import DocumentConverter, PdfFormatOption
        options = PdfPipelineOptions(artifacts_path=MODELS, do_ocr=True, do_table_structure=True)
        # Hybrid OCR preserves native text and recognizes scanned regions only.
        # The Chinese recognizer also reads Latin letters/digits; RapidOCR uses one language.
        options.ocr_options = RapidOcrOptions(lang=["ch"], backend="onnxruntime", mode=OcrMode.FULL_PAGE if force else OcrMode.DEFAULT)
        options.accelerator_options = AcceleratorOptions(num_threads=4, device=AcceleratorDevice.CPU)
        options.images_scale = 1.5
        options.generate_page_images = True
        options.generate_picture_images = True
        options.document_timeout = 240
        CONVERTERS[force] = DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=options)})
    return CONVERTERS[force]


def prune_cache():
    files = sorted(CACHE.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    total = 0
    for path in files:
        stat = path.stat()
        total += stat.st_size
        if total > 1024**3 or time.time() - stat.st_mtime > 7 * 86400:
            path.unlink(missing_ok=True)


def create_fast_ocr():
    """Reuse the already installed multilingual PP-OCRv6 models for scanned books."""
    from rapidocr import RapidOCR
    folder = MODELS / "RapidOcr"
    models = {
        "Det.model_path": folder / "PP-OCRv6_det_small.onnx",
        "Cls.model_path": folder / "ch_ppocr_mobile_v2.0_cls_mobile.onnx",
        "Rec.model_path": folder / "PP-OCRv6_rec_small.onnx",
        "Rec.rec_keys_path": folder / "ppocrv6_dict.txt",
    }
    missing = [str(path) for path in models.values() if not path.is_file()]
    if missing:
        raise FileNotFoundError("Local OCR models missing: " + ", ".join(missing))
    return RapidOCR(params={**{key: str(path) for key, path in models.items()},
                            "EngineConfig.onnxruntime.intra_op_num_threads": 3})


def fast_ocr():
    global FAST_OCR
    if FAST_OCR is None:
        FAST_OCR = create_fast_ocr()
    return FAST_OCR


def fast_ocr_second():
    global FAST_OCR_SECOND
    if FAST_OCR_SECOND is None:
        FAST_OCR_SECOND = create_fast_ocr()
    return FAST_OCR_SECOND


def parse_scanned_book(data):
    """Text-first path with at most two OCR pages in flight."""
    import numpy as np
    import pypdfium2
    document = pypdfium2.PdfDocument(data)
    try:
        if len(document) > 8:
            raise ValueError("At most eight selected pages are accepted")
        readers = (fast_ocr(), fast_ocr_second()) if len(document) > 1 else (fast_ocr(),)
        blocks, page_warnings = [], []

        def collect(index, result):
            texts = [line.strip() for line in (result.txts or []) if line.strip()]
            scores = result.scores or []
            if texts:
                blocks.append(dict(page=index + 1, kind="text", text="\n".join(texts)))
                if scores and sum(scores) / len(scores) < 0.6:
                    page_warnings.append(dict(page=index + 1, message="扫描文字识别置信度较低，请核对原页"))
            else:
                page_warnings.append(dict(page=index + 1, message="未识别到文字，请核对原页"))

        def render(index):
            page = document[index]
            try:
                bitmap = page.render(scale=1.5)
                try:
                    # The OCR thread may outlive this PDF bitmap.
                    return np.array(bitmap.to_pil(), copy=True)
                finally:
                    bitmap.close()
            finally:
                page.close()

        if len(document) == 1:
            collect(0, readers[0](render(0)))
        else:
            with ThreadPoolExecutor(max_workers=1) as first, ThreadPoolExecutor(max_workers=1) as second:
                workers = (first, second)
                pending = deque()
                for index in range(len(document)):
                    slot = index % 2
                    pending.append((index, workers[slot].submit(readers[slot], render(index))))
                    if len(pending) == 2:
                        finished, future = pending.popleft()
                        collect(finished, future.result())
                while pending:
                    finished, future = pending.popleft()
                    collect(finished, future.result())
        return dict(version=FAST_VERSION, pageCount=len(document), blocks=blocks,
                    pictures=[], pageWarnings=page_warnings, warnings=[], cacheHit=False)
    finally:
        document.close()


def parse(data, force, fast_scan=False):
    started = time.monotonic()
    key = hashlib.sha256((FAST_VERSION if fast_scan else VERSION).encode() + bytes([force]) + data).hexdigest()
    with LOCK:
        cached = CACHE / (key + ".json")
        if cached.exists():
            result = json.loads(cached.read_text(encoding="utf-8"))
            result.update(cacheHit=True, elapsedMs=int((time.monotonic() - started) * 1000))
            return result
        if fast_scan:
            response = parse_scanned_book(data)
            response["elapsedMs"] = int((time.monotonic() - started) * 1000)
            partial = cached.with_suffix(".tmp")
            partial.write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
            partial.replace(cached)
            prune_cache()
            return response
        from docling.datamodel.base_models import DocumentStream
        from docling_core.types.doc import PictureItem, TableItem, TextItem
        result = converter(force).convert(DocumentStream(name="selected-pages.pdf", stream=io.BytesIO(data)), raises_on_error=True, max_num_pages=8)
        doc = result.document
        if str(result.status.value) != "success":
            raise RuntimeError("Document conversion incomplete")
        blocks, pictures, warnings, layout_warnings = [], [], [], []
        with NativeText(data, enabled=not force) as native:
            for item, _ in doc.iterate_items():
                if not item.prov:
                    continue
                page = item.prov[0].page_no
                label = item.label.value
                if label in ("page_header", "page_footer"):
                    continue  # Repeated furniture must not displace document evidence.
                if isinstance(item, TableItem):
                    text = item.export_to_markdown(doc=doc)
                    text = restore_numeric_table(text, native.bounded(item,doc.pages[page].size.height))
                    blocks.append(dict(page=page, kind="table", text=text))
                    if table_warning(text):
                        layout_warnings.append(dict(page=page, message="表格有空列名或行列数量不一致，请核对原表；未自动推断列对应关系"))
                elif isinstance(item, PictureItem):
                    image = item.get_image(doc)
                    if image is None or min(image.size) < 90:
                        continue
                    # Return bounded crops; never store source paths in the response.
                    image = image.convert("RGB")
                    image.thumbnail((1600, 1600))
                    output = io.BytesIO()
                    image.save(output, "JPEG", quality=85)
                    if len(pictures) < 16:
                        pictures.append(dict(page=page, image="data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode(), caption=item.caption_text(doc)))
                    else:
                        warnings.append("图像较多，部分图像未发送至视觉补充")
                elif isinstance(item, TextItem):
                    text = native.restore(item, doc.pages[page].size.height)
                    if label == "section_header":
                        text = "## " + text
                    if text:
                        blocks.append(dict(page=page, kind="text", text=text))
        # Caption detection may leave a chart's title as a separate text item.
        # Carry a bounded, verbatim page context with the crop for retrieval.
        for picture in pictures:
            context = "\n".join(block["text"] for block in blocks if block["page"] == picture["page"] and block["kind"] == "text")[:500]
            picture["caption"] = (picture["caption"] + "\n" + context).strip()[:700]
        import math
        page_warnings = list(layout_warnings)
        for page, scores in result.confidence.pages.items():
            for name, label in (("ocr_score", "扫描文字"), ("table_score", "表格")):
                score = float(getattr(scores, name))
                if math.isfinite(score) and score < 0.5:
                    page_warnings.append(dict(page=page, message=label + "识别置信度较低，请核对原页"))
        response = dict(version=VERSION, pageCount=len(doc.pages), blocks=blocks, pictures=pictures, pageWarnings=page_warnings,
                        warnings=list(dict.fromkeys(warnings)), cacheHit=False,
                        elapsedMs=int((time.monotonic() - started) * 1000))
        partial = cached.with_suffix(".tmp")
        partial.write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
        partial.replace(cached)
        prune_cache()
        return response


@app.get("/health")
def health(request: Request):
    authorize(request)
    return dict(status="ok", version=VERSION, modelsReady=(MODELS / '.ready').exists())


@app.post("/parse")
async def parse_request(request: Request):
    authorize(request)
    if request.headers.get("Content-Type", "").split(";")[0] != "application/pdf":
        raise HTTPException(415, "PDF bytes required")
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > MAX_BYTES:
            raise HTTPException(413, "PDF too large")
    if not body.startswith(b"%PDF-"):
        raise HTTPException(400, "Invalid PDF")
    force = request.headers.get("X-Force-Ocr") == "true"
    fast_scan = request.headers.get("X-Fast-Scan") == "true"
    if fast_scan and not force:
        raise HTTPException(400, "Fast scan requires scanned pages")
    try:
        return await run_in_threadpool(parse, bytes(body), force, fast_scan)
    except Exception:
        # Detailed diagnostics remain local; never echo file content or paths.
        import logging
        logging.exception("PDF conversion failed")
        raise HTTPException(422, "本地 PDF 增强解析失败，请查看解析服务日志")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8741, limit_concurrency=4, backlog=8)
