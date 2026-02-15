# EcoLens Taxonomy Training Pipeline

This pipeline trains a MobileNetV3 classifier from your real user-confirmed scans and exports app-ready artifacts.

## What it produces

- `ml/artifacts/model/model.pte` (when ExecuTorch export succeeds)
- `ml/artifacts/model/labels.json` (class index mapping for iOS runtime)
- `ml/artifacts/model/model.torchscript.pt` (always exported)
- `ml/artifacts/model/training_metrics.json` (accuracy + run metadata)

## Prerequisites

- Python 3.10+
- Backend running and reachable (local or Railway)
- Confirmed training samples in backend (`/api/training/samples`)
- Google ID token for protected endpoints (if backend requires auth)

Install deps:

```bash
python3 -m venv ml/.venv
source ml/.venv/bin/activate
pip install -r ml/requirements.txt
```

## Option A: one command

```bash
python ml/run_pipeline.py \
  --api-base-url "https://<your-backend>" \
  --id-token "<GOOGLE_ID_TOKEN>" \
  --limit 8000 \
  --min-images-per-class 12 \
  --epochs 12 \
  --batch-size 32
```

## Option B: step-by-step

1. Export samples + taxonomy:

```bash
python ml/export_training_data.py \
  --api-base-url "https://<your-backend>" \
  --id-token "<GOOGLE_ID_TOKEN>" \
  --limit 8000 \
  --confirmed-only \
  --include-images
```

2. Build train/val/test dataset:

```bash
python ml/prepare_dataset.py \
  --export-json ml/artifacts/raw/training_export.json \
  --taxonomy-json ml/artifacts/raw/taxonomy.json \
  --output-dir ml/artifacts/dataset \
  --min-images-per-class 12
```

3. Train + export artifacts:

```bash
python ml/train_and_export.py \
  --dataset-dir ml/artifacts/dataset \
  --output-dir ml/artifacts/model \
  --epochs 12 \
  --batch-size 32 \
  --learning-rate 0.001 \
  --export-pte
```

## Public dataset training (no in-app scanning required)

You can bootstrap a stronger on-device model from **public COCO 2017 data**:

1. Build a taxonomy-mapped dataset from COCO:

```bash
python ml/build_public_coco_dataset.py \
  --output-dir ml/artifacts/public_dataset \
  --max-images-per-class 300 \
  --min-images-per-class 80
```

2. Train + export model artifacts from this public dataset:

```bash
python ml/train_and_export.py \
  --dataset-dir ml/artifacts/public_dataset \
  --output-dir ml/artifacts/model_public \
  --epochs 12 \
  --batch-size 32 \
  --num-workers 0 \
  --learning-rate 0.001 \
  --export-pte
```

This path uses mapped COCO classes (for example: backpack, smartphone, laptop, bottle, cup)
to generate a reusable baseline without collecting scans first.

## Broad coverage baseline (ImageNet)

For significantly wider everyday-object coverage (pen/page/book/cup/bottle/phone/laptop, etc.),
export a pretrained ImageNet MobileNet baseline:

```bash
python ml/export_imagenet_baseline.py \
  --output-dir ml/artifacts/model_imagenet \
  --model-name mobilenet_v3_small \
  --require-pte
```

Then copy artifacts to iOS bundle paths used by ExecuTorch:

```bash
cp ml/artifacts/model_imagenet/model.pte /Users/aditya/repos/hacks/ecolens-mobile/ios/ecolensmobile/model.pte
cp ml/artifacts/model_imagenet/labels.json /Users/aditya/repos/hacks/ecolens-mobile/ios/ecolensmobile/labels.json
```

Rebuild and reinstall the iOS app (hot reload is not enough for native model files):

```bash
cd /Users/aditya/repos/hacks/ecolens-mobile
npx expo run:ios --device
```

## Phase 2: Hybrid training (ImageNet + COCO/TACO fine-tune)

This is the recommended path for stronger EcoLens on-device accuracy:

1. Export broad ImageNet baseline (1000 classes) for coverage.
2. Build hybrid taxonomy dataset from COCO + TACO.
3. Fine-tune MobileNetV3 on the hybrid taxonomy dataset and export `.pte`.

Run in one command:

```bash
python ml/run_hybrid_pipeline.py \
  --artifacts-root ml/artifacts \
  --include-taco \
  --max-coco-images-per-class 260 \
  --max-taco-images-per-class 160 \
  --min-images-per-class 80 \
  --epochs 12 \
  --batch-size 32 \
  --num-workers 0 \
  --export-pte
```

Outputs:

- Broad baseline: `ml/artifacts/model_imagenet/model.pte` + `labels.json`
- Hybrid fine-tuned model: `ml/artifacts/model_hybrid/model.pte` + `labels.json`
- Hybrid dataset: `ml/artifacts/hybrid_dataset`

Use the hybrid model as primary in iOS, and optionally keep ImageNet as the second-pass coverage model.

## ExecuTorch `.pte` export notes

- `model.torchscript.pt` and `labels.json` are always generated.
- `.pte` export needs ExecuTorch Python package available in your environment.
- If `.pte` export fails, inspect `training_metrics.json` `pteError`.
- To make `.pte` mandatory, add `--require-pte`.

## Use artifacts in iOS app

Copy:

- `ml/artifacts/model/model.pte` -> `/Users/aditya/repos/hacks/ecolens-mobile/ios/ecolensmobile/model.pte`
- `ml/artifacts/model/labels.json` -> `/Users/aditya/repos/hacks/ecolens-mobile/ios/ecolensmobile/labels.json`

Then rebuild iOS app:

```bash
cd /Users/aditya/repos/hacks/ecolens-mobile
npx expo run:ios
```

## Recommended iteration loop

1. Ship current app.
2. Collect more confirmed scans from users.
3. Re-run pipeline weekly.
4. Track `testAcc` and fallback rate.
5. Promote only models that improve real-device behavior.
