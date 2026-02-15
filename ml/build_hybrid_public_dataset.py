#!/usr/bin/env python3
"""Build a hybrid public dataset (COCO + optional TACO) for EcoLens taxonomy training."""

from __future__ import annotations

import argparse
import json
import random
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import requests
from PIL import Image


TACO_ANNOTATION_URLS = [
    "https://raw.githubusercontent.com/pedropro/TACO/master/data/annotations.json",
    "https://raw.githubusercontent.com/pedropro/TACO/main/data/annotations.json",
]


@dataclass(frozen=True)
class TacoCropTask:
    image_id: int
    annotation_id: int
    file_name: str
    image_url: str
    split: str
    x: float
    y: float
    w: float
    h: float
    image_width: int
    image_height: int
    taxonomy_leaf: str
    source_category: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build hybrid COCO + TACO dataset for EcoLens taxonomy.")
    parser.add_argument("--output-dir", default="ml/artifacts/hybrid_dataset")
    parser.add_argument("--work-dir", default="ml/artifacts/hybrid_work")
    parser.add_argument(
        "--taxonomy-json",
        default="src/main/resources/taxonomy/ecolens-taxonomy-v1.json",
    )
    parser.add_argument(
        "--include-taco",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Include TACO annotations/images in addition to COCO.",
    )
    parser.add_argument(
        "--taco-annotations-json",
        default="",
        help="Optional local path to TACO annotations.json.",
    )
    parser.add_argument(
        "--taco-images-root",
        default="",
        help="Optional local folder for TACO images when annotations use relative file_name paths.",
    )
    parser.add_argument("--max-coco-images-per-class", type=int, default=260)
    parser.add_argument("--max-taco-images-per-class", type=int, default=160)
    parser.add_argument("--min-images-per-class", type=int, default=80)
    parser.add_argument("--min-box-size", type=int, default=48)
    parser.add_argument("--min-box-area-ratio", type=float, default=0.015)
    parser.add_argument("--train-ratio", type=float, default=0.8)
    parser.add_argument("--val-ratio", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--workers", type=int, default=8)
    return parser.parse_args()


def run(cmd: list[str]) -> None:
    print("$ " + " ".join(cmd))
    subprocess.run(cmd, check=True)


def load_taxonomy(path: Path) -> dict[str, dict[str, str]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, dict[str, str]] = {}
    for group in payload.get("groups", []) or []:
        parent_label = str(group.get("label", "")).strip()
        for klass in group.get("classes", []) or []:
            class_id = str(klass.get("id", "")).strip()
            if not class_id:
                continue
            out[class_id] = {
                "name": str(klass.get("label", class_id)).strip() or class_id,
                "category": parent_label,
            }
    return out


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


def download_file(url: str, dest: Path, timeout_sec: int = 45) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with requests.get(url, timeout=timeout_sec, stream=True) as response:
        response.raise_for_status()
        with dest.open("wb") as handle:
            for chunk in response.iter_content(chunk_size=1024 * 256):
                if chunk:
                    handle.write(chunk)


def resolve_taco_annotations_path(args: argparse.Namespace, work_dir: Path) -> Path | None:
    if args.taco_annotations_json:
        local = Path(args.taco_annotations_json)
        return local if local.exists() else None

    target = work_dir / "taco" / "annotations.json"
    if target.exists():
        return target

    for url in TACO_ANNOTATION_URLS:
        try:
            print(f"Downloading TACO annotations: {url}")
            download_file(url, target, timeout_sec=45)
            return target
        except Exception:
            continue
    return None


def infer_taco_taxonomy_leaf(category_name: str) -> str | None:
    text = category_name.strip().lower()
    if not text:
        return None

    if "bottle" in text:
        if "glass" in text:
            return "glass_bottle"
        if "plastic" in text or "pet" in text:
            return "single_use_plastic_bottle"
        return "single_use_plastic_bottle"
    if "can" in text:
        return "aluminum_can"
    if "cup" in text:
        if "paper" in text:
            return "paper_cup"
        if "plastic" in text:
            return "paper_cup"
        return "paper_cup"
    if "straw" in text:
        return "straw_plastic"
    if "wrapper" in text or "sachet" in text:
        return "snack_wrapper"
    if "carton" in text:
        return "paper_bag"
    if "bag" in text:
        if "paper" in text:
            return "paper_shopping_bag"
        if "plastic" in text:
            return "plastic_bag"
        return "plastic_bag"
    if "fork" in text or "spoon" in text or "knife" in text or "cutlery" in text:
        return "plastic_fork_spoon"
    if "plate" in text:
        return "paper_plate"
    if "container" in text or "takeout" in text:
        if "foam" in text or "styro" in text:
            return "styrofoam_box"
        if "fiber" in text or "paper" in text:
            return "takeout_container_fiber"
        return "takeout_container_plastic"
    if "paper" in text:
        return "paper_bag"
    if "toothbrush" in text:
        return "toothbrush_plastic"
    if "battery" in text:
        return "battery_disposable"
    if "tissue" in text:
        return "tissue_box"
    return None


def clamp_bbox(task: TacoCropTask, pad_ratio: float = 0.04) -> tuple[int, int, int, int]:
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


def resolve_taco_image_source(image_entry: dict[str, Any], taco_images_root: Path | None) -> tuple[str, bool]:
    # returns (source, is_url)
    for key in ("coco_url", "flickr_url"):
        value = str(image_entry.get(key, "")).strip()
        if value.startswith("http://") or value.startswith("https://"):
            return value, True

    file_name = str(image_entry.get("file_name", "")).strip()
    if file_name.startswith("http://") or file_name.startswith("https://"):
        return file_name, True
    if file_name and taco_images_root is not None:
        return str((taco_images_root / file_name).resolve()), False
    return "", False


def process_taco_task(
    task: TacoCropTask,
    images_cache_dir: Path,
    crops_tmp_dir: Path,
    taco_images_root: Path | None,
) -> dict[str, Any] | None:
    local_image = images_cache_dir / f"{task.image_id}.jpg"
    if not local_image.exists():
        if not task.image_url:
            return None
        try:
            if task.image_url.startswith("http://") or task.image_url.startswith("https://"):
                download_file(task.image_url, local_image, timeout_sec=45)
            else:
                candidate = Path(task.image_url)
                if not candidate.exists():
                    return None
                local_image.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(candidate, local_image)
        except Exception:
            return None

    try:
        with Image.open(local_image) as image:
            image = image.convert("RGB")
            left, top, right, bottom = clamp_bbox(task)
            cropped = image.crop((left, top, right, bottom))
            out_dir = crops_tmp_dir / task.taxonomy_leaf
            out_dir.mkdir(parents=True, exist_ok=True)
            out_name = f"taco_{task.image_id}_{task.annotation_id}.jpg"
            out_path = out_dir / out_name
            cropped.save(out_path, format="JPEG", quality=92)
    except Exception:
        return None

    return {
        "taxonomyLeaf": task.taxonomy_leaf,
        "sourceCategory": task.source_category,
        "source": "taco",
        "sourceImageId": task.image_id,
        "annotationId": task.annotation_id,
        "tempCropPath": str(out_path),
    }


def append_taco_samples(
    output_dir: Path,
    work_dir: Path,
    taxonomy_lookup: dict[str, dict[str, str]],
    args: argparse.Namespace,
) -> tuple[int, int]:
    annotations_path = resolve_taco_annotations_path(args, work_dir)
    if annotations_path is None:
        print("Warning: TACO annotations unavailable. Continuing with COCO-only dataset.")
        return 0, 0

    payload = json.loads(annotations_path.read_text(encoding="utf-8"))
    categories = {
        int(category.get("id")): str(category.get("name", "")).strip()
        for category in (payload.get("categories", []) or [])
    }
    images = {int(image.get("id")): image for image in (payload.get("images", []) or [])}
    taco_images_root = Path(args.taco_images_root) if args.taco_images_root else None

    per_class_tasks: dict[str, list[TacoCropTask]] = {}
    min_box_size = max(1, int(args.min_box_size))
    min_box_area_ratio = max(0.0, float(args.min_box_area_ratio))

    for ann in payload.get("annotations", []) or []:
        if int(ann.get("iscrowd", 0)) == 1:
            continue
        image_id = int(ann.get("image_id", -1))
        image_entry = images.get(image_id)
        if image_entry is None:
            continue

        category_name = categories.get(int(ann.get("category_id", -1)), "")
        taxonomy_leaf = infer_taco_taxonomy_leaf(category_name)
        if not taxonomy_leaf or taxonomy_leaf not in taxonomy_lookup:
            continue

        bbox = ann.get("bbox") or []
        if len(bbox) != 4:
            continue
        x, y, w, h = [float(value) for value in bbox]
        if w < min_box_size or h < min_box_size:
            continue

        width = int(image_entry.get("width", 0))
        height = int(image_entry.get("height", 0))
        if width <= 0 or height <= 0:
            continue
        if (w * h) / float(width * height) < min_box_area_ratio:
            continue

        source, _ = resolve_taco_image_source(image_entry, taco_images_root)
        if not source:
            continue

        task = TacoCropTask(
            image_id=image_id,
            annotation_id=int(ann.get("id", -1)),
            file_name=str(image_entry.get("file_name", "")),
            image_url=source,
            split="taco",
            x=x,
            y=y,
            w=w,
            h=h,
            image_width=width,
            image_height=height,
            taxonomy_leaf=taxonomy_leaf,
            source_category=category_name,
        )
        per_class_tasks.setdefault(taxonomy_leaf, []).append(task)

    if not per_class_tasks:
        print("Warning: No usable TACO tasks after mapping/filtering.")
        return 0, 0

    rng = random.Random(args.seed + 17)
    selected_tasks: list[TacoCropTask] = []
    for class_key, tasks in sorted(per_class_tasks.items()):
        rng.shuffle(tasks)
        selected_tasks.extend(tasks[: max(1, int(args.max_taco_images_per_class))])

    images_cache_dir = work_dir / "taco" / "images_cache"
    crops_tmp_dir = work_dir / "taco" / "crops_tmp"
    if crops_tmp_dir.exists():
        shutil.rmtree(crops_tmp_dir)
    crops_tmp_dir.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, int(args.workers))) as pool:
        futures = [
            pool.submit(process_taco_task, task, images_cache_dir, crops_tmp_dir, taco_images_root)
            for task in selected_tasks
        ]
        for future in as_completed(futures):
            row = future.result()
            if row:
                rows.append(row)

    if not rows:
        print("Warning: TACO image download/crop produced zero samples.")
        return len(selected_tasks), 0

    rows_by_class: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        rows_by_class.setdefault(str(row["taxonomyLeaf"]), []).append(row)

    copied = 0
    for class_key, class_rows in rows_by_class.items():
        rng.shuffle(class_rows)
        train_n, val_n, test_n = split_counts(
            len(class_rows),
            float(args.train_ratio),
            float(args.val_ratio),
        )
        split_names = (["train"] * train_n) + (["val"] * val_n) + (["test"] * test_n)
        for row, split_name in zip(class_rows, split_names):
            src = Path(str(row["tempCropPath"]))
            if not src.exists():
                continue
            dst = output_dir / split_name / class_key / src.name
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            copied += 1

    return len(selected_tasks), copied


def rebuild_metadata(
    output_dir: Path,
    taxonomy_lookup: dict[str, dict[str, str]],
    min_images_per_class: int,
) -> None:
    split_dirs = [output_dir / "train", output_dir / "val", output_dir / "test"]
    class_counts: dict[str, dict[str, int]] = {}
    for split_dir in split_dirs:
        split_name = split_dir.name
        if not split_dir.exists():
            continue
        for class_dir in split_dir.iterdir():
            if not class_dir.is_dir():
                continue
            image_count = len([path for path in class_dir.iterdir() if path.is_file()])
            class_counts.setdefault(class_dir.name, {"train": 0, "val": 0, "test": 0})
            class_counts[class_dir.name][split_name] = image_count

    kept_classes = []
    for class_key, counts in sorted(class_counts.items()):
        total = counts["train"] + counts["val"] + counts["test"]
        if total >= min_images_per_class:
            kept_classes.append(class_key)
            continue
        # Drop underrepresented classes from all splits.
        for split_name in ("train", "val", "test"):
            class_dir = output_dir / split_name / class_key
            if class_dir.exists():
                shutil.rmtree(class_dir, ignore_errors=True)

    if len(kept_classes) < 2:
        raise SystemExit(
            f"Need at least 2 classes after filtering min_images_per_class={min_images_per_class}. "
            f"Found {len(kept_classes)}."
        )

    class_to_index = {class_key: idx for idx, class_key in enumerate(sorted(kept_classes))}
    id2label: dict[str, dict[str, str]] = {}
    for class_key, index in class_to_index.items():
        meta = taxonomy_lookup.get(class_key, {})
        id2label[str(index)] = {
            "name": meta.get("name", class_key.replace("_", " ").title()),
            "classKey": class_key,
            "category": meta.get("category", ""),
        }

    rows: list[dict[str, Any]] = []
    split_sizes = {"train": 0, "val": 0, "test": 0}
    for split_name in ("train", "val", "test"):
        split_dir = output_dir / split_name
        if not split_dir.exists():
            continue
        for class_key in sorted(class_to_index.keys()):
            class_dir = split_dir / class_key
            if not class_dir.exists():
                continue
            for image_path in sorted(class_dir.iterdir()):
                if not image_path.is_file():
                    continue
                rows.append(
                    {
                        "split": split_name,
                        "classKey": class_key,
                        "classIndex": class_to_index[class_key],
                        "relativePath": str(image_path.relative_to(output_dir)),
                    }
                )
                split_sizes[split_name] += 1

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
                "source": "hybrid_coco_taco",
                "classCount": len(class_to_index),
                "splitSizes": split_sizes,
                "rows": rows,
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"Hybrid dataset prepared at {output_dir}")
    print(f"Classes kept: {len(class_to_index)}")
    print(
        "Split sizes: " + ", ".join(f"{name}={count}" for name, count in split_sizes.items())
    )


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir)
    work_dir = Path(args.work_dir)
    taxonomy_lookup = load_taxonomy(Path(args.taxonomy_json))

    # Step 1: COCO dataset build.
    run(
        [
            sys.executable,
            "ml/build_public_coco_dataset.py",
            "--output-dir",
            str(output_dir),
            "--work-dir",
            str(work_dir / "coco"),
            "--taxonomy-json",
            str(args.taxonomy_json),
            "--max-images-per-class",
            str(args.max_coco_images_per_class),
            "--min-images-per-class",
            str(max(1, int(args.min_images_per_class // 2))),
            "--min-box-size",
            str(args.min_box_size),
            "--min-box-area-ratio",
            str(args.min_box_area_ratio),
            "--train-ratio",
            str(args.train_ratio),
            "--val-ratio",
            str(args.val_ratio),
            "--seed",
            str(args.seed),
            "--workers",
            str(args.workers),
        ]
    )

    taco_selected = 0
    taco_copied = 0
    if args.include_taco:
        taco_selected, taco_copied = append_taco_samples(
            output_dir=output_dir,
            work_dir=work_dir,
            taxonomy_lookup=taxonomy_lookup,
            args=args,
        )
        print(f"TACO selected tasks: {taco_selected}")
        print(f"TACO copied crops: {taco_copied}")

    rebuild_metadata(
        output_dir=output_dir,
        taxonomy_lookup=taxonomy_lookup,
        min_images_per_class=max(1, int(args.min_images_per_class)),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
