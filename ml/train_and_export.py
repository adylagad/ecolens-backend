#!/usr/bin/env python3
"""Fine-tune MobileNetV3 and export labels + model artifacts."""

from __future__ import annotations

import argparse
import json
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any

np = None
torch = None
nn = None
DataLoader = None
datasets = None
models = None
transforms = None


@dataclass
class EpochMetrics:
    epoch: int
    train_loss: float
    train_acc: float
    val_loss: float
    val_acc: float


def import_training_deps() -> None:
    global np, torch, nn, DataLoader, datasets, models, transforms
    try:
        import numpy as np_module
        import torch as torch_module
        from torch import nn as nn_module
        from torch.utils.data import DataLoader as dataloader_class
        from torchvision import datasets as datasets_module, models as models_module, transforms as transforms_module
    except ModuleNotFoundError as ex:
        raise SystemExit(
            "Missing training dependencies (torch/torchvision/numpy). "
            "Run: pip install -r ml/requirements.txt"
        ) from ex

    np = np_module
    torch = torch_module
    nn = nn_module
    DataLoader = dataloader_class
    datasets = datasets_module
    models = models_module
    transforms = transforms_module


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train MobileNetV3 on prepared EcoLens dataset.")
    parser.add_argument("--dataset-dir", default="ml/artifacts/dataset")
    parser.add_argument("--output-dir", default="ml/artifacts/model")
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--learning-rate", type=float, default=1e-3)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--device",
        default="auto",
        choices=["auto", "cpu", "cuda", "mps"],
        help="Training device",
    )
    parser.add_argument(
        "--export-pte",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Attempt ExecuTorch .pte export after training",
    )
    parser.add_argument(
        "--require-pte",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Fail if .pte export is unavailable/fails",
    )
    return parser.parse_args()


def resolve_device(arg_device: str) -> torch.device:
    if arg_device == "cpu":
        return torch.device("cpu")
    if arg_device == "cuda":
        return torch.device("cuda")
    if arg_device == "mps":
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def build_transforms() -> tuple[transforms.Compose, transforms.Compose]:
    train_t = transforms.Compose(
        [
            transforms.Resize((256, 256)),
            transforms.RandomResizedCrop(224, scale=(0.7, 1.0)),
            transforms.RandomHorizontalFlip(),
            transforms.ColorJitter(brightness=0.15, contrast=0.15, saturation=0.1),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ]
    )
    eval_t = transforms.Compose(
        [
            transforms.Resize((256, 256)),
            transforms.CenterCrop(224),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ]
    )
    return train_t, eval_t


def load_label_metadata(dataset_dir: Path) -> dict[str, dict[str, str]]:
    labels_path = dataset_dir / "labels.json"
    if not labels_path.exists():
        return {}
    payload = json.loads(labels_path.read_text(encoding="utf-8"))
    id2label = payload.get("id2label", {}) if isinstance(payload, dict) else {}
    class_key_lookup: dict[str, dict[str, str]] = {}
    for _, meta in id2label.items():
        if not isinstance(meta, dict):
            continue
        class_key = str(meta.get("classKey", "")).strip()
        if class_key:
            class_key_lookup[class_key] = {
                "name": str(meta.get("name", class_key)).strip() or class_key,
                "category": str(meta.get("category", "")).strip(),
            }
    return class_key_lookup


def make_model(num_classes: int) -> nn.Module:
    model = models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.IMAGENET1K_V1)
    in_features = model.classifier[-1].in_features
    model.classifier[-1] = nn.Linear(in_features, num_classes)
    return model


def compute_accuracy(logits: torch.Tensor, targets: torch.Tensor) -> float:
    preds = logits.argmax(dim=1)
    correct = (preds == targets).sum().item()
    return correct / max(1, targets.size(0))


def evaluate(model: nn.Module, loader: DataLoader, criterion: nn.Module, device: torch.device) -> tuple[float, float]:
    model.eval()
    total_loss = 0.0
    total_correct = 0
    total_count = 0
    with torch.no_grad():
        for inputs, targets in loader:
            inputs = inputs.to(device)
            targets = targets.to(device)
            logits = model(inputs)
            loss = criterion(logits, targets)
            total_loss += loss.item() * targets.size(0)
            total_correct += int((logits.argmax(dim=1) == targets).sum().item())
            total_count += targets.size(0)
    if total_count == 0:
        return 0.0, 0.0
    return total_loss / total_count, total_correct / total_count


def export_pte(model: nn.Module, output_path: Path) -> None:
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


def main() -> int:
    args = parse_args()
    import_training_deps()
    set_seed(args.seed)
    device = resolve_device(args.device)

    dataset_dir = Path(args.dataset_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    train_t, eval_t = build_transforms()
    train_dataset = datasets.ImageFolder(dataset_dir / "train", transform=train_t)
    val_dataset = datasets.ImageFolder(dataset_dir / "val", transform=eval_t, allow_empty=True)
    test_dataset = datasets.ImageFolder(dataset_dir / "test", transform=eval_t, allow_empty=True)

    if len(train_dataset.classes) < 2:
        raise SystemExit("Need at least 2 classes to train a classifier.")
    if len(train_dataset) == 0:
        raise SystemExit("No training images found in dataset/train.")

    train_loader = DataLoader(train_dataset, batch_size=args.batch_size, shuffle=True, num_workers=2, pin_memory=False)
    val_loader = DataLoader(val_dataset, batch_size=args.batch_size, shuffle=False, num_workers=2, pin_memory=False)
    test_loader = DataLoader(test_dataset, batch_size=args.batch_size, shuffle=False, num_workers=2, pin_memory=False)

    model = make_model(num_classes=len(train_dataset.classes)).to(device)
    criterion = nn.CrossEntropyLoss()
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=args.learning_rate,
        weight_decay=args.weight_decay,
    )

    history: list[EpochMetrics] = []
    best_state: dict[str, Any] | None = None
    best_val_acc = -1.0

    for epoch in range(1, args.epochs + 1):
        model.train()
        total_loss = 0.0
        total_correct = 0
        total_count = 0

        for inputs, targets in train_loader:
            inputs = inputs.to(device)
            targets = targets.to(device)

            optimizer.zero_grad(set_to_none=True)
            logits = model(inputs)
            loss = criterion(logits, targets)
            loss.backward()
            optimizer.step()

            total_loss += loss.item() * targets.size(0)
            total_correct += int((logits.argmax(dim=1) == targets).sum().item())
            total_count += targets.size(0)

        train_loss = total_loss / max(1, total_count)
        train_acc = total_correct / max(1, total_count)
        val_loss, val_acc = evaluate(model, val_loader, criterion, device)
        history.append(
            EpochMetrics(
                epoch=epoch,
                train_loss=train_loss,
                train_acc=train_acc,
                val_loss=val_loss,
                val_acc=val_acc,
            )
        )
        print(
            f"epoch={epoch} train_loss={train_loss:.4f} train_acc={train_acc:.4f} "
            f"val_loss={val_loss:.4f} val_acc={val_acc:.4f}"
        )

        if val_acc > best_val_acc:
            best_val_acc = val_acc
            best_state = {k: v.detach().cpu() for k, v in model.state_dict().items()}

    if best_state is None:
        raise SystemExit("Training did not produce a valid model state.")

    model.load_state_dict(best_state)
    model = model.cpu().eval()

    test_loss, test_acc = evaluate(model, test_loader, criterion, torch.device("cpu"))
    print(f"test_loss={test_loss:.4f} test_acc={test_acc:.4f}")

    ckpt_path = output_dir / "mobilenetv3_best_state_dict.pt"
    torch.save(best_state, ckpt_path)

    example_input = torch.randn(1, 3, 224, 224)
    torchscript_path = output_dir / "model.torchscript.pt"
    scripted = torch.jit.trace(model, example_input)
    scripted.save(str(torchscript_path))

    label_meta_by_key = load_label_metadata(dataset_dir)
    id2label: dict[str, dict[str, str]] = {}
    for idx, class_key in enumerate(train_dataset.classes):
        meta = label_meta_by_key.get(class_key, {})
        id2label[str(idx)] = {
            "name": meta.get("name", class_key.replace("_", " ").title()),
            "classKey": class_key,
            "category": meta.get("category", ""),
        }
    labels_path = output_dir / "labels.json"
    labels_path.write_text(json.dumps({"id2label": id2label}, indent=2), encoding="utf-8")

    pte_path = output_dir / "model.pte"
    pte_status = "skipped"
    pte_error = ""
    if args.export_pte:
        try:
            export_pte(model, pte_path)
            pte_status = "exported"
        except Exception as ex:
            pte_status = "failed"
            pte_error = str(ex)
            if args.require_pte:
                raise

    metrics_path = output_dir / "training_metrics.json"
    metrics_path.write_text(
        json.dumps(
            {
                "epochs": args.epochs,
                "batchSize": args.batch_size,
                "learningRate": args.learning_rate,
                "classCount": len(train_dataset.classes),
                "trainImageCount": len(train_dataset),
                "valImageCount": len(val_dataset),
                "testImageCount": len(test_dataset),
                "bestValAcc": best_val_acc,
                "testAcc": test_acc,
                "torchscriptPath": str(torchscript_path),
                "labelsPath": str(labels_path),
                "ptePath": str(pte_path) if pte_path.exists() else "",
                "pteStatus": pte_status,
                "pteError": pte_error,
                "history": [m.__dict__ for m in history],
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"Saved checkpoint: {ckpt_path}")
    print(f"Saved TorchScript: {torchscript_path}")
    print(f"Saved labels: {labels_path}")
    print(f"PTE export status: {pte_status}")
    if pte_error:
        print(f"PTE export error: {pte_error}")
    print(f"Saved metrics: {metrics_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
