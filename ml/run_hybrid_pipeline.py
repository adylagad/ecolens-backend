#!/usr/bin/env python3
"""Run Phase 2 hybrid training pipeline:
1) Export broad ImageNet baseline model
2) Build hybrid COCO+TACO taxonomy dataset
3) Fine-tune model for EcoLens taxonomy
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run hybrid (ImageNet + COCO/TACO) training pipeline.")
    parser.add_argument("--artifacts-root", default="ml/artifacts")
    parser.add_argument("--taxonomy-json", default="src/main/resources/taxonomy/ecolens-taxonomy-v1.json")
    parser.add_argument("--include-taco", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--taco-annotations-json", default="")
    parser.add_argument("--taco-images-root", default="")
    parser.add_argument("--max-coco-images-per-class", type=int, default=260)
    parser.add_argument("--max-taco-images-per-class", type=int, default=160)
    parser.add_argument("--min-images-per-class", type=int, default=80)
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--learning-rate", type=float, default=1e-3)
    parser.add_argument("--num-workers", type=int, default=0)
    parser.add_argument("--export-pte", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--require-pte", action=argparse.BooleanOptionalAction, default=False)
    return parser.parse_args()


def run(cmd: list[str]) -> None:
    print("$ " + " ".join(cmd))
    subprocess.run(cmd, check=True)


def main() -> int:
    args = parse_args()
    root = Path(args.artifacts_root)
    imagenet_dir = root / "model_imagenet"
    hybrid_dataset_dir = root / "hybrid_dataset"
    hybrid_work_dir = root / "hybrid_work"
    hybrid_model_dir = root / "model_hybrid"

    export_cmd = [
        sys.executable,
        "ml/export_imagenet_baseline.py",
        "--output-dir",
        str(imagenet_dir),
        "--model-name",
        "mobilenet_v3_small",
    ]
    if args.require_pte:
        export_cmd.append("--require-pte")
    else:
        export_cmd.append("--no-require-pte")
    run(export_cmd)

    build_cmd = [
        sys.executable,
        "ml/build_hybrid_public_dataset.py",
        "--output-dir",
        str(hybrid_dataset_dir),
        "--work-dir",
        str(hybrid_work_dir),
        "--taxonomy-json",
        str(args.taxonomy_json),
        "--max-coco-images-per-class",
        str(args.max_coco_images_per_class),
        "--max-taco-images-per-class",
        str(args.max_taco_images_per_class),
        "--min-images-per-class",
        str(args.min_images_per_class),
    ]
    if args.include_taco:
        build_cmd.append("--include-taco")
    else:
        build_cmd.append("--no-include-taco")
    if args.taco_annotations_json.strip():
        build_cmd.extend(["--taco-annotations-json", args.taco_annotations_json.strip()])
    if args.taco_images_root.strip():
        build_cmd.extend(["--taco-images-root", args.taco_images_root.strip()])
    run(build_cmd)

    train_cmd = [
        sys.executable,
        "ml/train_and_export.py",
        "--dataset-dir",
        str(hybrid_dataset_dir),
        "--output-dir",
        str(hybrid_model_dir),
        "--epochs",
        str(args.epochs),
        "--batch-size",
        str(args.batch_size),
        "--learning-rate",
        str(args.learning_rate),
        "--num-workers",
        str(args.num_workers),
    ]
    if args.export_pte:
        train_cmd.append("--export-pte")
    else:
        train_cmd.append("--no-export-pte")
    if args.require_pte:
        train_cmd.append("--require-pte")
    else:
        train_cmd.append("--no-require-pte")
    run(train_cmd)

    print("\nHybrid pipeline complete.")
    print(f"ImageNet baseline model: {imagenet_dir}")
    print(f"Hybrid fine-tuned model: {hybrid_model_dir}")
    print(f"Hybrid training dataset: {hybrid_dataset_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
