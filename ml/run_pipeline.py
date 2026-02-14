#!/usr/bin/env python3
"""Run end-to-end export -> dataset prep -> training/export."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run EcoLens training pipeline end-to-end.")
    parser.add_argument("--api-base-url", required=True)
    parser.add_argument("--id-token", default="")
    parser.add_argument("--limit", type=int, default=6000)
    parser.add_argument("--min-images-per-class", type=int, default=12)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--learning-rate", type=float, default=1e-3)
    parser.add_argument("--export-pte", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--require-pte", action=argparse.BooleanOptionalAction, default=False)
    parser.add_argument("--artifacts-root", default="ml/artifacts")
    return parser.parse_args()


def run(cmd: list[str]) -> None:
    print(f"$ {' '.join(cmd)}")
    subprocess.run(cmd, check=True)


def main() -> int:
    args = parse_args()
    root = Path(args.artifacts_root)
    raw_dir = root / "raw"
    dataset_dir = root / "dataset"
    model_dir = root / "model"

    export_cmd = [
        sys.executable,
        "ml/export_training_data.py",
        "--api-base-url",
        args.api_base_url,
        "--output-dir",
        str(raw_dir),
        "--limit",
        str(args.limit),
        "--confirmed-only",
        "--include-images",
    ]
    if args.id_token.strip():
        export_cmd.extend(["--id-token", args.id_token.strip()])
    run(export_cmd)

    run(
        [
            sys.executable,
            "ml/prepare_dataset.py",
            "--export-json",
            str(raw_dir / "training_export.json"),
            "--taxonomy-json",
            str(raw_dir / "taxonomy.json"),
            "--output-dir",
            str(dataset_dir),
            "--min-images-per-class",
            str(args.min_images_per_class),
        ]
    )

    train_cmd = [
        sys.executable,
        "ml/train_and_export.py",
        "--dataset-dir",
        str(dataset_dir),
        "--output-dir",
        str(model_dir),
        "--epochs",
        str(args.epochs),
        "--batch-size",
        str(args.batch_size),
        "--learning-rate",
        str(args.learning_rate),
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

    print("\nPipeline complete.")
    print(f"Model artifacts: {model_dir}")
    print(f"Use {model_dir / 'model.pte'} and {model_dir / 'labels.json'} in iOS app.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
