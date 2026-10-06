"""Download only the layout/table models used by the worker to D:."""
import os
from pathlib import Path
from docling.utils.model_downloader import download_models

target = Path(os.environ["DOCLING_ARTIFACTS_PATH"])
download_models(output_dir=target, with_layout=True, with_tableformer=True,
                with_code_formula=False, with_picture_classifier=False,
                with_smolvlm=False, rapidocr_models=["onnxruntime:ch"])
print("Docling layout/table models ready:", target)
(target / '.ready').write_text('docling-2.130.0-rapidocr-v2', encoding='utf-8')
