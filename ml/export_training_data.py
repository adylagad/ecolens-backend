#!/usr/bin/env python3
"""Fetch confirmed training samples + taxonomy from EcoLens backend."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch /api/training/export and /api/training/taxonomy for model training."
    )
    parser.add_argument("--api-base-url", required=True, help="Backend base URL, e.g. https://...railway.app")
    parser.add_argument(
        "--output-dir",
        default="ml/artifacts/raw",
        help="Directory to write training_export.json + taxonomy.json",
    )
    parser.add_argument("--limit", type=int, default=6000, help="Max samples to request from export endpoint")
    parser.add_argument(
        "--confirmed-only",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Whether to export only user-confirmed samples (default: true)",
    )
    parser.add_argument(
        "--include-images",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Whether to include imageBase64 in export (default: true)",
    )
    parser.add_argument(
        "--id-token",
        default=os.getenv("GOOGLE_ID_TOKEN", ""),
        help="Google ID token for authenticated backends (or set GOOGLE_ID_TOKEN env var)",
    )
    parser.add_argument("--timeout-seconds", type=float, default=45.0, help="HTTP timeout in seconds")
    return parser.parse_args()


def normalize_base_url(value: str) -> str:
    return value.strip().rstrip("/")


def request_json(url: str, headers: dict[str, str], timeout_seconds: float) -> Any:
    try:
        import requests  # type: ignore
    except ModuleNotFoundError as ex:
        raise SystemExit(
            "Missing dependency 'requests'. Run: pip install -r ml/requirements.txt"
        ) from ex
    response = requests.get(url, headers=headers, timeout=timeout_seconds)
    response.raise_for_status()
    return response.json()


def build_headers(id_token: str) -> dict[str, str]:
    token = id_token.strip()
    if not token:
        return {}
    return {"Authorization": f"Bearer {token}"}


def main() -> int:
    args = parse_args()
    base_url = normalize_base_url(args.api_base_url)
    headers = build_headers(args.id_token)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    export_url = (
        f"{base_url}/api/training/export"
        f"?limit={args.limit}"
        f"&confirmedOnly={str(args.confirmed_only).lower()}"
        f"&includeImages={str(args.include_images).lower()}"
    )
    taxonomy_url = f"{base_url}/api/training/taxonomy"

    export_payload = request_json(export_url, headers=headers, timeout_seconds=args.timeout_seconds)
    taxonomy_payload = request_json(taxonomy_url, headers=headers, timeout_seconds=args.timeout_seconds)

    export_path = output_dir / "training_export.json"
    taxonomy_path = output_dir / "taxonomy.json"
    export_path.write_text(json.dumps(export_payload, indent=2), encoding="utf-8")
    taxonomy_path.write_text(json.dumps(taxonomy_payload, indent=2), encoding="utf-8")

    sample_count = len(export_payload.get("samples", []) or [])
    class_count = 0
    for group in taxonomy_payload.get("groups", []) or []:
        class_count += len(group.get("classes", []) or [])

    print(f"Wrote {export_path} ({sample_count} samples)")
    print(f"Wrote {taxonomy_path} ({class_count} taxonomy classes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
