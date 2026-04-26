#!/usr/bin/env python3
"""Prepare Qualcomm MobileNet-v2 LiteRT AOT assets for Snapdragon SM8850."""

from __future__ import annotations

import argparse
import json
import shutil
import ssl
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_DIR = ROOT / "build" / "aot_sm8850"
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets"
MODEL_ZIP = BUILD_DIR / "mobilenet_v2-tflite-float.zip"
EXTRACT_DIR = BUILD_DIR / "mobilenet_v2-tflite-float"
AI_PACK_NAME = "mobilenet_v2_sm8850"
AI_PACK_ROOT = ROOT / "ai_pack"
AI_PACK_DIR = AI_PACK_ROOT / AI_PACK_NAME
AI_PACK_MTK_DIR = AI_PACK_ROOT / f"{AI_PACK_NAME}_mtk"
RUNTIME_DIR = ROOT / "litert_npu_runtime_libraries"

HF_RELEASE_ASSETS_URL = (
    "https://huggingface.co/qualcomm/MobileNet-v2/resolve/main/release_assets.json"
)
RUNTIME_ZIP_URL = (
    "https://github.com/google-ai-edge/LiteRT/releases/download/"
    "v2.1.0rc1/litert_npu_runtime_libraries.zip"
)


def urlopen(url: str):
  try:
    import certifi

    context = ssl.create_default_context(cafile=certifi.where())
    return urllib.request.urlopen(url, context=context)
  except ImportError:
    return urllib.request.urlopen(url)


def download(url: str, dest: Path, force: bool = False) -> None:
  dest.parent.mkdir(parents=True, exist_ok=True)
  if dest.exists() and not force:
    print(f"Using existing {dest}")
    return
  print(f"Downloading {url}")
  with urlopen(url) as response, dest.open("wb") as output:
    shutil.copyfileobj(response, output)


def read_release_assets() -> dict:
  with urlopen(HF_RELEASE_ASSETS_URL) as response:
    return json.loads(response.read().decode("utf-8"))


def prepare_model(force: bool) -> Path:
  release_assets = read_release_assets()
  model_url = release_assets["precisions"]["float"]["universal_assets"]["tflite"][
    "download_url"
  ]
  download(model_url, MODEL_ZIP, force)
  shutil.rmtree(EXTRACT_DIR, ignore_errors=True)
  with zipfile.ZipFile(MODEL_ZIP) as archive:
    archive.extractall(BUILD_DIR)

  model_path = EXTRACT_DIR / "mobilenet_v2.tflite"
  labels_path = EXTRACT_DIR / "labels.txt"
  if not model_path.exists():
    raise FileNotFoundError(f"Expected model not found: {model_path}")

  (ASSETS_DIR / "model").mkdir(parents=True, exist_ok=True)
  (ASSETS_DIR / "labels").mkdir(parents=True, exist_ok=True)
  shutil.copy2(model_path, ASSETS_DIR / "model" / "mobilenet_v2_float.tflite")
  if labels_path.exists():
    shutil.copy2(labels_path, ASSETS_DIR / "labels" / "imagenet_labels.txt")
  print(f"CPU fallback assets copied into {ASSETS_DIR}")
  return model_path


def prepare_runtime(force: bool) -> None:
  runtime_zip = BUILD_DIR / "litert_npu_runtime_libraries.zip"
  if force:
    shutil.rmtree(RUNTIME_DIR, ignore_errors=True)
  if not RUNTIME_DIR.exists():
    download(RUNTIME_ZIP_URL, runtime_zip, force)
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(runtime_zip) as archive:
      archive.extractall(RUNTIME_DIR)

  fetch_script = RUNTIME_DIR / "fetch_qualcomm_library.sh"
  if fetch_script.exists():
    print("Fetching Qualcomm NPU runtime binaries")
    subprocess.run(["bash", str(fetch_script)], cwd=ROOT, check=True)
  else:
    print(f"Warning: {fetch_script} was not found; runtime libraries may be incomplete.")


def import_aot_packages():
  try:
    from ai_edge_litert.aot import aot_compile as aot_lib
    from ai_edge_litert.aot.ai_pack import export_lib as ai_pack_export
    from ai_edge_litert.aot.vendors import fallback_backend
    from ai_edge_litert.aot.vendors.qualcomm import target as qnn_target
  except ImportError as exc:
    raise SystemExit(
      "Missing LiteRT AOT Python packages. Install them in an Ubuntu 22.04 environment:\n"
      "  pip install ai-edge-litert-sdk-qualcomm==0.2.0 ai-edge-litert==2.1.2\n"
    ) from exc
  return aot_lib, ai_pack_export, fallback_backend, qnn_target


def ensure_ai_pack_gradle() -> None:
  build_file = AI_PACK_DIR / "build.gradle.kts"
  if build_file.exists():
    return
  build_file.write_text(
    'plugins { id("com.android.ai-pack") }\n\n'
    "aiPack {\n"
    '  packName = "mobilenet_v2_sm8850"\n'
    '  dynamicDelivery { deliveryType = "on-demand" }\n'
    "}\n",
    encoding="utf-8",
  )


def copy_device_targeting_config() -> None:
  candidates = list(AI_PACK_ROOT.rglob("device_targeting_configuration.xml"))
  if not candidates:
    print(
      "Warning: no device_targeting_configuration.xml found in AI Pack. "
      "The Gradle NPU bundle build will require it."
    )
    return
  shutil.copy2(candidates[0], ROOT / "app" / "device_targeting_configuration.xml")
  print("Copied device_targeting_configuration.xml into app/")


def compile_aot(model_path: Path) -> None:
  aot_lib, ai_pack_export, fallback_backend, qnn_target = import_aot_packages()
  target = [
    qnn_target.Target(qnn_target.SocModel.SM8850),
    fallback_backend.FallbackTarget(),
  ]
  print("Running LiteRT AOT compilation for Qualcomm SM8850")
  compiled_models = aot_lib.aot_compile(
    str(model_path),
    target=target,
    keep_going=False,
  )

  if hasattr(compiled_models, "compilation_report"):
    print(compiled_models.compilation_report())

  shutil.rmtree(AI_PACK_DIR, ignore_errors=True)
  shutil.rmtree(AI_PACK_MTK_DIR, ignore_errors=True)
  AI_PACK_ROOT.mkdir(parents=True, exist_ok=True)
  ai_pack_export.export(
    compiled_models,
    str(AI_PACK_ROOT),
    AI_PACK_NAME,
    "mobilenet_v2_float",
  )
  ensure_ai_pack_gradle()
  copy_device_targeting_config()
  print(f"AI Pack exported to {AI_PACK_DIR}")


def main() -> None:
  parser = argparse.ArgumentParser()
  parser.add_argument("--skip-aot", action="store_true", help="Only download CPU assets/runtime.")
  parser.add_argument("--force", action="store_true", help="Re-download and regenerate assets.")
  args = parser.parse_args()

  model_path = prepare_model(args.force)
  prepare_runtime(args.force)
  if not args.skip_aot:
    compile_aot(model_path)

  print("\nNext build commands:")
  print("  ./gradlew installDebug")
  print("  ./gradlew -PenableNpu=true bundleRelease")


if __name__ == "__main__":
  sys.exit(main())
