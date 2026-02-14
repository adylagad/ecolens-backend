#!/usr/bin/env python3
"""Convert exported training samples into an ImageFolder dataset."""

from __future__ import annotations

import argparse
import base64
import json
import random
import re
from collections import defaultdict
from io import BytesIO
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare train/val/test folders from training_export.json")
    parser.add_argument(
        "--export-json",
        default="ml/artifacts/raw/training_export.json",
        help="Path to training export payload",
    )
    parser.add_argument(
        "--taxonomy-json",
        default="ml/artifacts/raw/taxonomy.json",
        help="Path to taxonomy JSON",
    )
    parser.add_argument(
        "--output-dir",
        default="ml/artifacts/dataset",
        help="Output dataset root with train/val/test folders",
    )
    parser.add_argument(
        "--min-images-per-class",
        type=int,
        default=12,
        help="Drop classes with fewer valid images than this threshold",
    )
    parser.add_argument("--train-ratio", type=float, default=0.8)
    parser.add_argument("--val-ratio", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def safe_slug(value: str, fallback: str = "unknown_item") -> str:
    value = re.sub(r"[^a-z0-9]+", "_", (value or "").strip().lower()).strip("_")
    return value or fallback


def strip_data_url(image_b64: str) -> str:
    value = image_b64.strip()
    if value.startswith("data:") and "," in value:
        return value.split(",", 1)[1]
    return value


def decode_image_to_rgb_jpeg(image_b64: str) -> bytes:
    try:
        from PIL import Image  # type: ignore
    except ModuleNotFoundError as ex:
        raise SystemExit(
            "Missing dependency 'Pillow'. Run: pip install -r ml/requirements.txt"
        ) from ex
    raw = base64.b64decode(strip_data_url(image_b64), validate=True)
    image = Image.open(BytesIO(raw)).convert("RGB")
    out = BytesIO()
    image.save(out, format="JPEG", quality=92)
    return out.getvalue()


def split_counts(total: int, train_ratio: float, val_ratio: float) -> tuple[int, int, int]:
    if total <= 1:
        return total, 0, 0
    if total == 2:
        return 1, 1, 0

    train_n = int(round(total * train_ratio))
    val_n = int(round(total * val_ratio))
    train_n = max(1, min(train_n, total - 2))
    val_n = max(1, min(val_n, total - train_n - 1))
    test_n = total - train_n - val_n
    if test_n < 1:
        if train_n > val_n:
            train_n -= 1
        else:
            val_n -= 1
        test_n = 1
    return train_n, val_n, test_n


def build_taxonomy_lookup(taxonomy_payload: dict[str, Any]) -> dict[str, dict[str, str]]:
    lookup: dict[str, dict[str, str]] = {}
    for group in taxonomy_payload.get("groups", []) or []:
        parent_id = str(group.get("id", "")).strip()
        parent_label = str(group.get("label", "")).strip()
        for klass in group.get("classes", []) or []:
            class_id = str(klass.get("id", "")).strip()
            class_label = str(klass.get("label", "")).strip()
            if class_id:
                lookup[class_id] = {
                    "name": class_label or class_id,
                    "parentId": parent_id,
                    "parentLabel": parent_label,
                }
    return lookup


def main() -> int:
    args = parse_args()
    export_path = Path(args.export_json)
    taxonomy_path = Path(args.taxonomy_json)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    export_payload = load_json(export_path)
    taxonomy_payload = load_json(taxonomy_path) if taxonomy_path.exists() else {"groups": []}
    taxonomy_lookup = build_taxonomy_lookup(taxonomy_payload)

    class_samples: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for sample in export_payload.get("samples", []) or []:
        image_b64 = str(sample.get("imageBase64") or "").strip()
        if not image_b64:
            continue
        taxonomy_leaf = str(sample.get("taxonomyLeaf") or "").strip()
        final_label = str(sample.get("finalLabel") or "").strip()
        class_key = taxonomy_leaf or safe_slug(final_label)
        if not class_key:
            continue
        class_samples[class_key].append(sample)

    kept_classes = {
        class_key: samples
        for class_key, samples in class_samples.items()
        if len(samples) >= args.min_images_per_class
    }
    if not kept_classes:
        raise SystemExit(
            f"No classes met min_images_per_class={args.min_images_per_class}. "
            "Collect more confirmed samples or lower the threshold."
        )

    class_keys = sorted(kept_classes.keys())
    class_to_index = {class_key: idx for idx, class_key in enumerate(class_keys)}
    id2label: dict[str, dict[str, str]] = {}
    for class_key, idx in class_to_index.items():
        taxonomy_meta = taxonomy_lookup.get(class_key, {})
        fallback_name = safe_slug(class_key).replace("_", " ").title()
        id2label[str(idx)] = {
            "name": taxonomy_meta.get("name") or fallback_name,
            "classKey": class_key,
            "category": taxonomy_meta.get("parentLabel") or "",
        }

    for split in ("train", "val", "test"):
        split_dir = output_dir / split
        split_dir.mkdir(parents=True, exist_ok=True)
        for class_key in class_keys:
            (split_dir / class_key).mkdir(parents=True, exist_ok=True)

    rng = random.Random(args.seed)
    manifest_rows: list[dict[str, Any]] = []
    dropped_images = 0
    saved_images = 0

    for class_key in class_keys:
        samples = kept_classes[class_key][:]
        rng.shuffle(samples)
        train_n, val_n, test_n = split_counts(len(samples), args.train_ratio, args.val_ratio)
        split_names = (["train"] * train_n) + (["val"] * val_n) + (["test"] * test_n)
        for sample, split_name in zip(samples, split_names):
            image_b64 = str(sample.get("imageBase64") or "").strip()
            sample_id = str(sample.get("id") or f"{class_key}_{saved_images}")
            try:
                jpeg_bytes = decode_image_to_rgb_jpeg(image_b64)
            except Exception:
                dropped_images += 1
                continue

            filename = f"{safe_slug(sample_id, 'sample')}.jpg"
            out_path = output_dir / split_name / class_key / filename
            out_path.write_bytes(jpeg_bytes)
            saved_images += 1
            manifest_rows.append(
                {
                    "id": sample_id,
                    "split": split_name,
                    "classKey": class_key,
                    "classIndex": class_to_index[class_key],
                    "relativePath": str(out_path.relative_to(output_dir)),
                    "capturedAt": sample.get("capturedAt"),
                    "sourceEngine": sample.get("sourceEngine"),
                    "sourceRuntime": sample.get("sourceRuntime"),
                }
            )

    (output_dir / "labels.json").write_text(
        json.dumps({"id2label": id2label}, indent=2),
        encoding="utf-8",
    )
    (output_dir / "class_to_index.json").write_text(
        json.dumps(class_to_index, indent=2),
        encoding="utf-8",
    )
    (output_dir / "dataset_manifest.json").write_text(
        json.dumps(
            {
                "classCount": len(class_keys),
                "savedImages": saved_images,
                "droppedImages": dropped_images,
                "minImagesPerClass": args.min_images_per_class,
                "rows": manifest_rows,
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"Prepared dataset at {output_dir}")
    print(f"Classes kept: {len(class_keys)}")
    print(f"Images saved: {saved_images}")
    print(f"Images dropped: {dropped_images}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
