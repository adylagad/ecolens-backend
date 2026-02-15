#!/usr/bin/env python3
"""Build an ImageFolder dataset from public COCO 2017 annotations + images.

This script maps selected COCO categories to EcoLens taxonomy leaf classes,
downloads only the required images, crops object boxes, and writes a
train/val/test folder structure consumable by train_and_export.py.
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import requests
from PIL import Image


COCO_ANNOTATIONS_URL = "http://images.cocodataset.org/annotations/annotations_trainval2017.zip"

# COCO category name -> taxonomy leaf id
COCO_TO_TAXONOMY: dict[str, str] = {
    "backpack": "backpack",
    "handbag": "messenger_bag",
    "laptop": "laptop",
    "cell phone": "smartphone",
    "tv": "tv_monitor",
    "book": "notebook",
    "toothbrush": "toothbrush_plastic",
    "bottle": "single_use_plastic_bottle",
    "cup": "paper_cup",
    "fork": "plastic_fork_spoon",
    "knife": "plastic_fork_spoon",
    "spoon": "plastic_fork_spoon",
}


@dataclass(frozen=True)
class CropTask:
    split: str
    file_name: str
    image_id: int
    annotation_id: int
    x: float
    y: float
    w: float
    h: float
    image_width: int
    image_height: int
    taxonomy_leaf: str
    source_category: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a public COCO-derived dataset mapped to EcoLens taxonomy."
    )
    parser.add_argument(
        "--output-dir",
        default="ml/artifacts/public_dataset",
        help="Output dataset root (train/val/test folders).",
    )
    parser.add_argument(
        "--work-dir",
        default="ml/artifacts/public_coco_work",
        help="Working dir for downloaded COCO files and intermediate crops.",
    )
    parser.add_argument(
        "--taxonomy-json",
        default="src/main/resources/taxonomy/ecolens-taxonomy-v1.json",
        help="Path to EcoLens taxonomy JSON.",
    )
    parser.add_argument(
        "--max-images-per-class",
        type=int,
        default=300,
        help="Maximum cropped images to keep per taxonomy class.",
    )
    parser.add_argument(
        "--min-images-per-class",
        type=int,
        default=80,
        help="Drop classes with fewer crops than this threshold.",
    )
    parser.add_argument(
        "--min-box-size",
        type=int,
        default=48,
        help="Minimum bbox width/height in pixels.",
    )
    parser.add_argument(
        "--min-box-area-ratio",
        type=float,
        default=0.015,
        help="Minimum bbox area / image area.",
    )
    parser.add_argument("--train-ratio", type=float, default=0.8)
    parser.add_argument("--val-ratio", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--workers", type=int, default=8, help="Parallel workers for download/crop.")
    return parser.parse_args()


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


def load_taxonomy(path: Path) -> dict[str, dict[str, str]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, dict[str, str]] = {}
    for group in payload.get("groups", []) or []:
        parent_label = str(group.get("label", "")).strip()
        parent_id = str(group.get("id", "")).strip()
        for klass in group.get("classes", []) or []:
            class_id = str(klass.get("id", "")).strip()
            if not class_id:
                continue
            out[class_id] = {
                "name": str(klass.get("label", class_id)).strip() or class_id,
                "category": parent_label,
                "parentId": parent_id,
            }
    return out


def download_file(url: str, dest: Path, timeout_sec: int = 60) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with requests.get(url, stream=True, timeout=timeout_sec) as response:
        response.raise_for_status()
        with dest.open("wb") as handle:
            for chunk in response.iter_content(chunk_size=1024 * 256):
                if chunk:
                    handle.write(chunk)


def ensure_coco_annotations(work_dir: Path) -> tuple[Path, Path]:
    annotations_zip = work_dir / "downloads" / "annotations_trainval2017.zip"
    ann_dir = work_dir / "annotations"
    train_json = ann_dir / "instances_train2017.json"
    val_json = ann_dir / "instances_val2017.json"
    if train_json.exists() and val_json.exists():
        return train_json, val_json

    print(f"Downloading COCO annotations: {COCO_ANNOTATIONS_URL}")
    download_file(COCO_ANNOTATIONS_URL, annotations_zip)
    ann_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(annotations_zip, "r") as zf:
        zf.extract("annotations/instances_train2017.json", path=work_dir)
        zf.extract("annotations/instances_val2017.json", path=work_dir)

    extracted_root = work_dir / "annotations"
    if not train_json.exists() or not val_json.exists():
        nested_root = work_dir / "annotations" / "annotations"
        if nested_root.exists():
            nested_train = nested_root / "instances_train2017.json"
            nested_val = nested_root / "instances_val2017.json"
            if nested_train.exists() and nested_val.exists():
                train_json.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(nested_train), str(train_json))
                shutil.move(str(nested_val), str(val_json))
                shutil.rmtree(nested_root, ignore_errors=True)

    if not train_json.exists() or not val_json.exists():
        raise SystemExit("Failed to extract COCO annotations JSON files.")
    return train_json, val_json


def load_crop_tasks(
    annotations_json: Path,
    split: str,
    rng: random.Random,
    max_per_class: int,
    min_box_size: int,
    min_box_area_ratio: float,
) -> dict[str, list[CropTask]]:
    payload = json.loads(annotations_json.read_text(encoding="utf-8"))
    categories = {
        int(c.get("id")): str(c.get("name", "")).strip().lower()
        for c in (payload.get("categories", []) or [])
    }
    images = {int(i.get("id")): i for i in (payload.get("images", []) or [])}

    per_class: dict[str, list[CropTask]] = {}
    for ann in payload.get("annotations", []) or []:
        if int(ann.get("iscrowd", 0)) == 1:
            continue
        category_name = categories.get(int(ann.get("category_id", -1)), "")
        taxonomy_leaf = COCO_TO_TAXONOMY.get(category_name)
        if not taxonomy_leaf:
            continue
        bbox = ann.get("bbox") or []
        if len(bbox) != 4:
            continue
        x, y, w, h = [float(v) for v in bbox]
        if w < min_box_size or h < min_box_size:
            continue

        image_meta = images.get(int(ann.get("image_id", -1)))
        if not image_meta:
            continue
        img_w = int(image_meta.get("width", 0))
        img_h = int(image_meta.get("height", 0))
        if img_w <= 0 or img_h <= 0:
            continue
        area_ratio = (w * h) / float(img_w * img_h)
        if area_ratio < min_box_area_ratio:
            continue
        file_name = str(image_meta.get("file_name", "")).strip()
        if not file_name:
            continue

        task = CropTask(
            split=split,
            file_name=file_name,
            image_id=int(ann.get("image_id")),
            annotation_id=int(ann.get("id")),
            x=x,
            y=y,
            w=w,
            h=h,
            image_width=img_w,
            image_height=img_h,
            taxonomy_leaf=taxonomy_leaf,
            source_category=category_name,
        )
        per_class.setdefault(taxonomy_leaf, []).append(task)

    selected: dict[str, list[CropTask]] = {}
    for class_key, tasks in per_class.items():
        rng.shuffle(tasks)
        selected[class_key] = tasks[:max_per_class]
    return selected


def clamp_bbox(task: CropTask, pad_ratio: float = 0.04) -> tuple[int, int, int, int]:
    pad_x = task.w * pad_ratio
    pad_y = task.h * pad_ratio
    left = max(0, int(task.x - pad_x))
    top = max(0, int(task.y - pad_y))
    right = min(task.image_width, int(task.x + task.w + pad_x))
    bottom = min(task.image_height, int(task.y + task.h + pad_y))
    if right <= left:
        right = min(task.image_width, left + 1)
    if bottom <= top:
        bottom = min(task.image_height, top + 1)
    return left, top, right, bottom


def process_task(task: CropTask, images_root: Path, crops_root: Path) -> dict[str, Any] | None:
    image_url = f"http://images.cocodataset.org/{task.split}2017/{task.file_name}"
    local_image = images_root / task.split / task.file_name
    local_image.parent.mkdir(parents=True, exist_ok=True)
    if not local_image.exists():
        try:
            download_file(image_url, local_image, timeout_sec=45)
        except Exception:
            return None

    try:
        with Image.open(local_image) as image:
            image = image.convert("RGB")
            left, top, right, bottom = clamp_bbox(task)
            cropped = image.crop((left, top, right, bottom))
            out_dir = crops_root / task.taxonomy_leaf
            out_dir.mkdir(parents=True, exist_ok=True)
            out_name = f"{task.split}_{task.image_id}_{task.annotation_id}.jpg"
            out_path = out_dir / out_name
            cropped.save(out_path, format="JPEG", quality=92)
    except Exception:
        return None

    return {
        "taxonomyLeaf": task.taxonomy_leaf,
        "sourceCategory": task.source_category,
        "sourceSplit": task.split,
        "sourceImageId": task.image_id,
        "annotationId": task.annotation_id,
        "cropPath": str(out_path),
    }


def main() -> int:
    args = parse_args()
    rng = random.Random(args.seed)

    output_dir = Path(args.output_dir)
    work_dir = Path(args.work_dir)
    images_root = work_dir / "images"
    crops_root = work_dir / "crops"

    taxonomy_lookup = load_taxonomy(Path(args.taxonomy_json))
    missing_taxonomy_keys = sorted({v for v in COCO_TO_TAXONOMY.values() if v not in taxonomy_lookup})
    if missing_taxonomy_keys:
        raise SystemExit(
            "Taxonomy is missing mapped class keys: "
            + ", ".join(missing_taxonomy_keys)
        )

    train_json, val_json = ensure_coco_annotations(work_dir)
    selected_train = load_crop_tasks(
        train_json,
        split="train",
        rng=rng,
        max_per_class=max(1, int(args.max_images_per_class)),
        min_box_size=max(1, int(args.min_box_size)),
        min_box_area_ratio=max(0.0, float(args.min_box_area_ratio)),
    )
    selected_val = load_crop_tasks(
        val_json,
        split="val",
        rng=rng,
        max_per_class=max(1, int(args.max_images_per_class // 2)),
        min_box_size=max(1, int(args.min_box_size)),
        min_box_area_ratio=max(0.0, float(args.min_box_area_ratio)),
    )

    all_tasks: list[CropTask] = []
    all_class_keys = sorted(set(selected_train.keys()) | set(selected_val.keys()))
    for class_key in all_class_keys:
        merged = selected_train.get(class_key, []) + selected_val.get(class_key, [])
        rng.shuffle(merged)
        all_tasks.extend(merged[: args.max_images_per_class])

    print(f"Selected classes from COCO mapping: {len(all_class_keys)}")
    print(f"Selected crop tasks: {len(all_tasks)}")
    if not all_tasks:
        raise SystemExit("No crop tasks selected. Try lowering --min-box-size/--min-box-area-ratio.")

    if crops_root.exists():
        shutil.rmtree(crops_root)
    crops_root.mkdir(parents=True, exist_ok=True)

    manifest_rows: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, int(args.workers))) as pool:
        futures = [pool.submit(process_task, t, images_root, crops_root) for t in all_tasks]
        for future in as_completed(futures):
            row = future.result()
            if row:
                manifest_rows.append(row)

    if output_dir.exists():
        shutil.rmtree(output_dir)
    for split_name in ("train", "val", "test"):
        (output_dir / split_name).mkdir(parents=True, exist_ok=True)

    by_class: dict[str, list[dict[str, Any]]] = {}
    for row in manifest_rows:
        class_key = str(row.get("taxonomyLeaf", "")).strip()
        if not class_key:
            continue
        by_class.setdefault(class_key, []).append(row)

    kept_classes: list[str] = []
    dataset_rows: list[dict[str, Any]] = []
    for class_key in sorted(by_class.keys()):
        rows = by_class[class_key][:]
        rng.shuffle(rows)
        if len(rows) < args.min_images_per_class:
            continue
        kept_classes.append(class_key)
        train_n, val_n, test_n = split_counts(len(rows), args.train_ratio, args.val_ratio)
        split_list = (["train"] * train_n) + (["val"] * val_n) + (["test"] * test_n)
        for row, split_name in zip(rows, split_list):
            src = Path(str(row["cropPath"]))
            dst = output_dir / split_name / class_key / src.name
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            dataset_rows.append(
                {
                    **row,
                    "split": split_name,
                    "relativePath": str(dst.relative_to(output_dir)),
                }
            )

    if len(kept_classes) < 2:
        raise SystemExit(
            f"Need at least 2 classes after filtering; got {len(kept_classes)}. "
            "Lower --min-images-per-class or raise --max-images-per-class."
        )

    class_to_index = {class_key: idx for idx, class_key in enumerate(sorted(kept_classes))}
    id2label: dict[str, dict[str, str]] = {}
    for class_key, idx in class_to_index.items():
        meta = taxonomy_lookup[class_key]
        id2label[str(idx)] = {
            "name": meta.get("name", class_key),
            "classKey": class_key,
            "category": meta.get("category", ""),
        }

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
                "source": "coco2017",
                "mappedClasses": COCO_TO_TAXONOMY,
                "keptClassCount": len(kept_classes),
                "selectedCropTasks": len(all_tasks),
                "savedCrops": len(manifest_rows),
                "rows": dataset_rows,
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    split_counts_summary: dict[str, int] = {"train": 0, "val": 0, "test": 0}
    for row in dataset_rows:
        split_counts_summary[row["split"]] = split_counts_summary.get(row["split"], 0) + 1

    print(f"Prepared dataset at {output_dir}")
    print(f"Kept classes: {len(kept_classes)}")
    print(
        "Split sizes: "
        + ", ".join(f"{k}={v}" for k, v in split_counts_summary.items())
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
