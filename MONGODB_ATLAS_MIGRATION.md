# MongoDB Atlas Migration (Phase 1)

This branch adds a safe migration bootstrap without replacing current H2/JPA runtime yet.

## What was added

- MongoDB Atlas migration properties in `application.properties`
- Admin endpoints (JWT protected):
- `GET /api/admin/mongodb/status`
- `POST /api/admin/mongodb/migrate`
- `POST /api/admin/mongodb/runtime-check` (write/read/delete probe)
- Startup runner (optional) for automatic backfill
- Backfill service that upserts:
  - `products` table -> Atlas `products` collection
  - `scan_history` table -> Atlas `scan_history` collection

## Environment variables

Set these before running backend:

- `MONGODB_ATLAS_URI`
- `MONGODB_ATLAS_DATABASE` (default: `ecolens`)
- `MONGODB_ATLAS_PRODUCTS_COLLECTION` (default: `products`)
- `MONGODB_ATLAS_HISTORY_COLLECTION` (default: `scan_history`)
- `MONGODB_ATLAS_MIGRATION_ENABLED=true`
- `MONGODB_ATLAS_RUNTIME_ENABLED=false` (set `true` to make Product/History runtime read-write use Mongo)
- `MONGODB_ATLAS_RUN_ON_STARTUP=false` (recommended)

## Run migration manually

1. Start backend with the env vars above.
2. Call status endpoint:

```bash
curl -H "Authorization: Bearer <google-id-token>" \
  http://localhost:8080/api/admin/mongodb/status
```

3. Trigger migration:

```bash
curl -X POST -H "Authorization: Bearer <google-id-token>" \
  http://localhost:8080/api/admin/mongodb/migrate
```

4. Verify runtime probe:

```bash
curl -X POST -H "Authorization: Bearer <google-id-token>" \
  http://localhost:8080/api/admin/mongodb/runtime-check
```

## Runtime switch (phase 2)

When `MONGODB_ATLAS_RUNTIME_ENABLED=true`, runtime reads/writes for:

- product/catalog lookup + auto-learn writes (`ProductService`)
- history save/list/stats (`HistoryService`)

are routed to Atlas first, with automatic fallback to JPA/H2 on errors.
