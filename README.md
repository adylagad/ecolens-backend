# EcoLens Backend

Spring Boot backend for EcoLens: product recognition, eco scoring, scan history, training data capture, and optional MongoDB Atlas runtime/migration.

## Table of Contents

1. [What This Service Does](#what-this-service-does)
2. [Tech Stack](#tech-stack)
3. [Architecture at a Glance](#architecture-at-a-glance)
4. [Local Setup](#local-setup)
5. [Configuration](#configuration)
6. [Authentication and Authorization](#authentication-and-authorization)
7. [API Reference](#api-reference)
8. [Scoring and Catalog Behavior](#scoring-and-catalog-behavior)
9. [Data Storage](#data-storage)
10. [MongoDB Atlas Migration and Runtime Mode](#mongodb-atlas-migration-and-runtime-mode)
11. [ML Pipeline Integration](#ml-pipeline-integration)
12. [Project Structure](#project-structure)
13. [Testing](#testing)
14. [Troubleshooting](#troubleshooting)

## What This Service Does

- Accepts recognition requests from label text and/or base64 image input.
- Resolves products from a seeded catalog with exact/fuzzy matching.
- Computes eco score (0-100), CO2 score, and feature-based adjustments.
- Stores and serves user scan history with summary stats.
- Captures user-confirmed training samples with taxonomy alignment.
- Supports Gemini-backed image label detection and explanation generation.
- Supports phased migration from H2/JPA to MongoDB Atlas.

## Tech Stack

- Java 17
- Spring Boot 4.0.2
- Spring Web MVC
- Spring Data JPA + H2 (default runtime)
- Spring Security OAuth2 Resource Server (Google JWT validation)
- MongoDB Java Sync Driver (optional migration/runtime)
- OpenAI Java SDK dependency present, but current runtime LLM implementation uses Gemini HTTP APIs
- Maven Wrapper (`./mvnw`)

## Architecture at a Glance

- `RecognitionController` delegates to `ProductService`.
- `ProductService` handles:
  - input routing (text/image),
  - catalog match strategy (`exact`, fuzzy, inferred, fallback, auto-learned),
  - scoring composition (`catalog + co2 + adjustments`),
  - optional LLM explanation generation.
- `HistoryController` + `HistoryService` manage authenticated user history and streak stats.
- `TrainingController` + `TrainingDataService` manage taxonomy-backed training samples and export.
- `MongoAtlasMigrationController` + migration/runtime services support phased Atlas adoption.

## Local Setup

### Prerequisites

- JDK 17+
- Internet access if using Gemini APIs

### Run

```bash
./mvnw -q spring-boot:run
```

Service starts on `http://localhost:8080`.

### Verify

- H2 Console: `http://localhost:8080/h2-console`
  - JDBC URL: `jdbc:h2:mem:ecolensdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`
  - User: `sa`
  - Password: empty

## Configuration

Default values live in `src/main/resources/application.properties`.

### Core

- `spring.application.name=ecolens-backend`
- In-memory H2 datasource + seeded `data.sql`.

### LLM and Vision (Gemini)

- `llm.provider=gemini`
- `GEMINI_MODEL` (default configured: `gemma-3-1b-it`)
- `GEMINI_VISION_MODEL` (default configured: `gemini-2.5-flash-lite`)
- API key resolution order:
  1. `GOOGLE_API_KEY`
  2. `gemini.api.key`
  3. `GEMINI_API_KEY`

If key/model calls fail, service returns safe fallbacks (no hard failure to client).

### Auth (Google token verification)

- `AUTH_GOOGLE_AUDIENCES` (comma-separated allowed audiences)
- `auth.google.jwk-set-uri` default: `https://www.googleapis.com/oauth2/v3/certs`

### Scoring (config-driven)

All under `scoring.*`, including:

- score bounds (`min-score`, `max-score`)
- weight mix (`catalog-weight`, `co2-weight`)
- thresholding (`high-impact-threshold`, `history-greener-threshold`, etc.)
- feature adjustments (`single-use-penalty`, `reusable-bonus`, ...)

### Catalog learning/coverage

All under `catalog.*`, including:

- `catalog.auto-learn-enabled`
- `catalog.auto-learn-require-image`
- `catalog.auto-learn-min-confidence`
- `catalog.coverage.*` values for response confidence/coverage metadata

## Authentication and Authorization

JWT-protected endpoints:

- `/api/auth/me`
- `/api/history/**`
- `/api/admin/**`

Public endpoints:

- `/api/recognize`
- `/api/training/**`
- `/h2-console/**`

History endpoints always derive user identity from JWT (`sub`, else `email`) and ignore request body `userId` for authorization decisions.

## API Reference

Base URL: `http://localhost:8080`

### Recognition

`POST /api/recognize`

Request:

```json
{
  "detectedLabel": "plastic bottle",
  "confidence": 0.88,
  "imageBase64": "data:image/jpeg;base64,..."
}
```

Behavior:

- If `imageBase64` is present, backend attempts Gemini vision label detection first.
- Falls back to provided `detectedLabel` if vision detection is empty.
- Always returns `200` with safe fallback payload if internal recognition fails.

### Auth

`GET /api/auth/me` (Bearer token required)

Returns Google token subject/profile claims (`sub`, `email`, `name`, `picture`, `aud`, `iss`).

### History

Bearer token required for all endpoints:

- `POST /api/history`
- `GET /api/history?highImpactOnly=false`
- `DELETE /api/history/{historyId}`
- `GET /api/history/stats`

`/stats` includes:

- average score,
- high impact count,
- greener choices count,
- avoided single-use count (current ISO week),
- current and best eco streak.

### Training

- `GET /api/training/taxonomy`
- `POST /api/training/samples`
- `GET /api/training/samples?limit=200&confirmedOnly=true&includeImages=false`
- `GET /api/training/export?limit=1000&confirmedOnly=true&includeImages=true`
- `GET /api/training/stats`

Training samples are taxonomy-resolved and include metadata like source engine/runtime, app version, and optional image hashes/base64 payload.

### MongoDB Admin

Bearer token required:

- `GET /api/admin/mongodb/status`
- `POST /api/admin/mongodb/migrate`
- `POST /api/admin/mongodb/runtime-check`

## Scoring and Catalog Behavior

- Catalog lookup supports exact and fuzzy matching with aliases.
- For unknown labels, metadata inference can derive defaults (material, single-use/reusable, lifecycle, recyclability).
- Optional auto-learning can persist new catalog entries when confidence and policy gates are met.
- Response includes explainability fields:
  - `scoreFactors`,
  - `catalogContribution` / `co2Contribution`,
  - `featureAdjustment`,
  - `catalogMatchStrategy`,
  - `catalogCoverage`,
  - `scoringVersion`.

## Data Storage

Default runtime:

- H2 in-memory DB
- Seed catalog loaded from `src/main/resources/data.sql`

Primary tables/entities:

- `products`
- `scan_history`
- `training_samples`

## MongoDB Atlas Migration and Runtime Mode

Migration and runtime toggles are controlled by `mongodb.atlas.*` properties:

- `MONGODB_ATLAS_URI`
- `MONGODB_ATLAS_DATABASE` (default `ecolens`)
- `MONGODB_ATLAS_PRODUCTS_COLLECTION` (default `products`)
- `MONGODB_ATLAS_HISTORY_COLLECTION` (default `scan_history`)
- `MONGODB_ATLAS_MIGRATION_ENABLED`
- `MONGODB_ATLAS_RUNTIME_ENABLED`
- `MONGODB_ATLAS_RUN_ON_STARTUP`

Recommended rollout:

1. Enable migration only.
2. Run migration endpoints and verify status/runtime check.
3. Enable runtime after confidence is established.

See `MONGODB_ATLAS_MIGRATION.md` for the phase guide.

## ML Pipeline Integration

`ml/` contains scripts to:

- export confirmed training samples + taxonomy,
- prepare datasets,
- train MobileNetV3 classifiers,
- export TorchScript / ExecuTorch artifacts,
- bootstrap with public datasets (COCO/ImageNet baseline paths).

See `ml/README.md` for end-to-end commands.

## Project Structure

```text
src/main/java/com/ecolens/ecolens_backend/
  config/
  controller/
  dto/
  model/
  repository/
  security/
  service/
src/main/resources/
  application.properties
  data.sql
  taxonomy/ecolens-taxonomy-v1.json
ml/
```

## Testing

```bash
./mvnw -q test
```

Current test suite includes service-level tests for smart fallback behavior and training data service behavior.

## Troubleshooting

- `401` on protected routes:
  - Ensure Bearer token is valid Google JWT and audience is in `AUTH_GOOGLE_AUDIENCES`.
- Empty/weak recognition labels from image:
  - Verify Gemini API key env vars and model names.
- Mongo runtime failures:
  - Keep `MONGODB_ATLAS_RUNTIME_ENABLED=false` and use migration/status probes first.
- Data resets on restart:
  - Expected with in-memory H2 unless you switch to persistent storage/runtime mode.
