#!/usr/bin/env python3
"""Export a broad-coverage ImageNet MobileNetV3 model for ExecuTorch iOS.

This script exports:
- model.pte
- model.torchscript.pt
- labels.json (id2label, category, summary, suggestion, ecoScore)

Use this as an on-device baseline with much wider class coverage than a
small custom taxonomy model.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

np = None
torch = None
models = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export pretrained ImageNet MobileNetV3 to ExecuTorch .pte")
    parser.add_argument("--output-dir", default="ml/artifacts/model_imagenet")
    parser.add_argument(
        "--model-name",
        default="mobilenet_v3_small",
        choices=["mobilenet_v3_small", "mobilenet_v3_large"],
    )
    parser.add_argument(
        "--require-pte",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Fail if ExecuTorch .pte export is unavailable/fails.",
    )
    return parser.parse_args()


def import_deps() -> None:
    global np, torch, models
    try:
        import numpy as np_module
        import torch as torch_module
        from torchvision import models as models_module
    except ModuleNotFoundError as ex:
        raise SystemExit(
            "Missing dependencies. Run: pip install -r ml/requirements.txt"
        ) from ex
    np = np_module
    torch = torch_module
    models = models_module


def slugify(value: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")
    return cleaned or "unknown_item"


def infer_runtime_category(label: str) -> str:
    text = label.lower()
    if any(token in text for token in ["bottle", "water jug", "thermos", "flask", "canteen", "tumbler"]):
        if any(token in text for token in ["plastic", "pet", "disposable", "single"]):
            return "single-use-plastic-bottle"
        return "reusable-hydration"
    if any(token in text for token in ["cup", "mug", "plate", "bowl", "straw", "wrapper", "carton", "bag"]):
        return "packaging"
    if any(token in text for token in ["laptop", "computer", "notebook computer", "keyboard", "monitor", "screen", "phone", "cellphone", "smartphone", "tablet"]):
        return "electronic-device"
    if any(token in text for token in ["pen", "pencil", "book", "notebook", "paper", "envelope"]):
        return "durable-household"
    if any(token in text for token in ["shoe", "backpack", "bag", "wallet", "watch", "umbrella"]):
        return "durable-household"
    if any(token in text for token in ["fork", "knife", "spoon", "napkin", "toothbrush", "toothpaste"]):
        return "single-use-item"
    return "general-object"


def infer_eco_score(label: str, category: str) -> int:
    text = label.lower()
    score = 55
    if category == "reusable-hydration":
        score += 20
    elif category == "single-use-plastic-bottle":
        score -= 20
    elif category == "packaging":
        score -= 9
    elif category == "single-use-item":
        score -= 12
    elif category == "electronic-device":
        score -= 7
    elif category == "durable-household":
        score += 4
    elif category == "general-object":
        score -= 2

    if any(token in text for token in ["plastic", "styrofoam", "disposable"]):
        score -= 8
    if any(token in text for token in ["glass", "steel", "stainless", "reusable", "refillable"]):
        score += 6
    if any(token in text for token in ["paper", "cardboard", "wood"]):
        score += 2
    if any(token in text for token in ["battery", "electronics", "electronic"]):
        score -= 6

    return max(1, min(99, score))


def summary_for_category(category: str, eco_score: int) -> str:
    if category == "single-use-plastic-bottle":
        return "High single-use plastic impact. Prefer refillable containers when possible."
    if category == "single-use-item":
        return "Likely disposable profile. Reuse and material choice can reduce footprint."
    if category == "packaging":
        return "Packaging-heavy item. Reusables or low-waste options generally score better."
    if category == "reusable-hydration":
        return "Reusable hydration item. Repeated use usually improves lifecycle impact."
    if category == "electronic-device":
        return "Electronics have embodied manufacturing impact; longevity and repairability matter."
    if category == "durable-household":
        return "Durable item profile. Longer use and maintenance can improve sustainability."
    if eco_score >= 70:
        return "Moderate-to-good footprint estimate based on inferred class."
    if eco_score <= 35:
        return "Relatively high footprint estimate based on inferred class."
    return "Moderate footprint estimate; behavior and material choice can improve impact."


def suggestion_for_category(category: str) -> str:
    if category == "single-use-plastic-bottle":
        return "Use a refillable steel or glass bottle."
    if category == "single-use-item":
        return "Switch to reusable alternatives where possible."
    if category == "packaging":
        return "Choose minimal packaging and reusable containers."
    if category == "reusable-hydration":
        return "Keep and reuse this hydration item."
    if category == "electronic-device":
        return "Extend device life, repair when possible, and recycle e-waste responsibly."
    if category == "durable-household":
        return "Maintain and reuse instead of replacing frequently."
    return "Consider reusable or lower-impact alternatives."


def export_pte(model: Any, output_path: Path) -> None:
    try:
        import executorch.exir as exir  # type: ignore
    except Exception as ex:
        raise RuntimeError(
            "ExecuTorch is not installed. Install from pytorch/executorch before exporting .pte."
        ) from ex

    to_edge = getattr(exir, "to_edge", None)
    if to_edge is None:
        raise RuntimeError("executorch.exir.to_edge is unavailable in installed ExecuTorch package.")

    model.eval()
    example_input = torch.randn(1, 3, 224, 224)
    exported = torch.export.export(model.cpu(), (example_input,))
    edge_manager = to_edge(exported)
    try:
        et_program = edge_manager.to_executorch()
    except TypeError:
        et_program = edge_manager.to_executorch(None)

    data = getattr(et_program, "buffer", None)
    if callable(data):
        data = data()
    if data is None:
        raise RuntimeError("ExecuTorch export returned no binary buffer.")
    output_path.write_bytes(data)


def build_model(model_name: str) -> tuple[Any, list[str], str]:
    if model_name == "mobilenet_v3_large":
        weights = models.MobileNet_V3_Large_Weights.IMAGENET1K_V2
        model = models.mobilenet_v3_large(weights=weights)
    else:
        weights = models.MobileNet_V3_Small_Weights.IMAGENET1K_V1
        model = models.mobilenet_v3_small(weights=weights)
    categories = list(weights.meta.get("categories", []))
    if not categories:
        raise SystemExit("Could not read ImageNet class categories from torchvision weights metadata.")
    return model.eval().cpu(), categories, str(weights)


def build_labels(categories: list[str]) -> dict[str, Any]:
    id2label: dict[str, dict[str, Any]] = {}
    for index, raw_name in enumerate(categories):
        name = str(raw_name).strip() or f"Class {index}"
        category = infer_runtime_category(name)
        eco_score = infer_eco_score(name, category)
        id2label[str(index)] = {
            "name": name,
            "classKey": slugify(name),
            "category": category,
            "ecoScore": eco_score,
            "summary": summary_for_category(category, eco_score),
            "suggestion": suggestion_for_category(category),
        }
    return {"id2label": id2label}


def main() -> int:
    args = parse_args()
    import_deps()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    model, categories, weights_name = build_model(args.model_name)

    labels_payload = build_labels(categories)
    labels_path = output_dir / "labels.json"
    labels_path.write_text(json.dumps(labels_payload, indent=2), encoding="utf-8")

    example_input = torch.randn(1, 3, 224, 224)
    torchscript_path = output_dir / "model.torchscript.pt"
    scripted = torch.jit.trace(model, example_input)
    scripted.save(str(torchscript_path))

    pte_path = output_dir / "model.pte"
    pte_status = "skipped"
    pte_error = ""
    try:
        export_pte(model, pte_path)
        pte_status = "exported"
    except Exception as ex:
        pte_status = "failed"
        pte_error = str(ex)
        if args.require_pte:
            raise

    metadata_path = output_dir / "export_metadata.json"
    metadata_path.write_text(
        json.dumps(
            {
                "modelName": args.model_name,
                "weights": weights_name,
                "classCount": len(categories),
                "torchscriptPath": str(torchscript_path),
                "labelsPath": str(labels_path),
                "ptePath": str(pte_path) if pte_path.exists() else "",
                "pteStatus": pte_status,
                "pteError": pte_error,
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"Exported labels: {labels_path}")
    print(f"Exported TorchScript: {torchscript_path}")
    print(f"PTE export status: {pte_status}")
    if pte_error:
        print(f"PTE export error: {pte_error}")
    print(f"Export metadata: {metadata_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
