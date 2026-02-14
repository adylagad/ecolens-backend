package com.ecolens.ecolens_backend.service;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.regex;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.config.MongoAtlasProperties;
import com.ecolens.ecolens_backend.model.Product;
import com.ecolens.ecolens_backend.model.ScanHistoryEntry;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

@Service
public class MongoAtlasRuntimeStore {

    private static final Logger log = LoggerFactory.getLogger(MongoAtlasRuntimeStore.class);

    private final MongoAtlasProperties mongoAtlasProperties;

    public MongoAtlasRuntimeStore(MongoAtlasProperties mongoAtlasProperties) {
        this.mongoAtlasProperties = mongoAtlasProperties;
    }

    public boolean isRuntimeEnabled() {
        return mongoAtlasProperties.isRuntimeEnabled()
                && !safeText(mongoAtlasProperties.getUri(), "").isBlank();
    }

    public List<Product> findAllProducts() {
        return withProductsCollection(products -> {
            List<Product> out = new ArrayList<>();
            FindIterable<Document> docs = products.find();
            for (Document doc : docs) {
                out.add(toProduct(doc));
            }
            return out;
        });
    }

    public Optional<Product> findProductByNameIgnoreCase(String name) {
        String value = safeText(name, "");
        if (value.isBlank()) {
            return Optional.empty();
        }
        return withProductsCollection(products -> {
            String regexPattern = "^" + Pattern.quote(value) + "$";
            Document doc = products.find(regex("name", regexPattern, "i")).first();
            return Optional.ofNullable(doc).map(this::toProduct);
        });
    }

    public Optional<Product> findFirstProductByCategoryIgnoreCase(String category) {
        String value = safeText(category, "");
        if (value.isBlank()) {
            return Optional.empty();
        }
        return withProductsCollection(products -> {
            String regexPattern = "^" + Pattern.quote(value) + "$";
            Document doc = products.find(regex("category", regexPattern, "i")).first();
            return Optional.ofNullable(doc).map(this::toProduct);
        });
    }

    public Product saveProduct(Product product) {
        return withProductsCollection(products -> {
            String name = safeText(product.getName(), "Unknown Product");
            String category = safeText(product.getCategory(), "unknown");
            String nameKey = normalizeKey(name);
            String categoryKey = normalizeKey(category);

            Document doc = toProductDocument(product)
                    .append("nameKey", nameKey)
                    .append("categoryKey", categoryKey);

            if (product.getId() != null) {
                doc.append("legacyId", product.getId());
                products.replaceOne(eq("legacyId", product.getId()), doc, new ReplaceOptions().upsert(true));
                Document saved = products.find(eq("legacyId", product.getId())).first();
                return saved == null ? toProduct(doc) : toProduct(saved);
            }

            products.replaceOne(and(eq("nameKey", nameKey), eq("categoryKey", categoryKey)),
                    doc,
                    new ReplaceOptions().upsert(true));
            Document saved = products.find(and(eq("nameKey", nameKey), eq("categoryKey", categoryKey))).first();
            return saved == null ? toProduct(doc) : toProduct(saved);
        });
    }

    public List<Double> findAllProductCarbonImpactsOrdered() {
        return withProductsCollection(products -> {
            List<Double> out = new ArrayList<>();
            for (Document doc : products.find().sort(Sorts.ascending("co2Gram"))) {
                Object raw = doc.get("co2Gram");
                if (raw instanceof Number number) {
                    out.add(number.doubleValue());
                }
            }
            return out;
        });
    }

    public ScanHistoryEntry saveHistoryEntry(ScanHistoryEntry entry) {
        return withHistoryCollection(history -> {
            Document doc = toHistoryDocument(entry)
                    .append("source", "mongodb_runtime");
            history.insertOne(doc);
            return toHistoryEntry(doc);
        });
    }

    public List<ScanHistoryEntry> findHistoryByUser(String userId) {
        String normalizedUserId = safeText(userId, "");
        return withHistoryCollection(history -> {
            List<ScanHistoryEntry> out = new ArrayList<>();
            for (Document doc : history.find(eq("userId", normalizedUserId)).sort(Sorts.descending("scannedAt"))) {
                out.add(toHistoryEntry(doc));
            }
            return out;
        });
    }

    public List<ScanHistoryEntry> findHistoryByUserHighImpact(String userId, int threshold) {
        String normalizedUserId = safeText(userId, "");
        return withHistoryCollection(history -> {
            List<ScanHistoryEntry> out = new ArrayList<>();
            for (Document doc : history
                    .find(and(eq("userId", normalizedUserId), lt("ecoScore", threshold)))
                    .sort(Sorts.descending("scannedAt"))) {
                out.add(toHistoryEntry(doc));
            }
            return out;
        });
    }

    private <T> T withProductsCollection(MongoCollectionFunction<T> function) {
        return withCollection(safeText(mongoAtlasProperties.getProductsCollection(), "products"), function);
    }

    private <T> T withHistoryCollection(MongoCollectionFunction<T> function) {
        return withCollection(safeText(mongoAtlasProperties.getHistoryCollection(), "scan_history"), function);
    }

    private <T> T withCollection(String collectionName, MongoCollectionFunction<T> function) {
        String uri = safeText(mongoAtlasProperties.getUri(), "");
        if (uri.isBlank()) {
            throw new IllegalStateException("MongoDB Atlas URI is not configured.");
        }
        String dbName = safeText(mongoAtlasProperties.getDatabase(), "ecolens");
        int connectTimeoutMs = Math.max(500, mongoAtlasProperties.getConnectTimeoutMs());
        int socketTimeoutMs = Math.max(500, mongoAtlasProperties.getSocketTimeoutMs());
        int serverSelectionTimeoutMs = Math.max(500, mongoAtlasProperties.getServerSelectionTimeoutMs());

        ConnectionString connectionString = new ConnectionString(uri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(serverSelectionTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> {
                    builder.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS);
                    builder.readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS);
                })
                .build();

        try (MongoClient client = MongoClients.create(settings)) {
            MongoDatabase db = client.getDatabase(dbName);
            MongoCollection<Document> collection = db.getCollection(collectionName);
            return function.apply(collection);
        } catch (Exception ex) {
            log.warn("Mongo runtime store operation failed (collection={}): {}", collectionName, ex.getMessage());
            throw ex;
        }
    }

    private Document toProductDocument(Product product) {
        return new Document("name", safeText(product.getName(), "Unknown Product"))
                .append("category", safeText(product.getCategory(), "unknown"))
                .append("ecoScore", product.getEcoScore())
                .append("co2Gram", product.getCarbonImpact())
                .append("recyclability", safeText(product.getRecyclability(), "Unknown"))
                .append("altRecommendation", safeText(product.getAlternativeRecommendation(), ""))
                .append("explanation", safeText(product.getExplanation(), ""))
                .append("material", safeText(product.getMaterial(), ""))
                .append("isReusable", product.getReusable())
                .append("isSingleUse", product.getSingleUse())
                .append("recycledContentPercent", product.getRecycledContentPercent())
                .append("lifecycleType", safeText(product.getLifecycleType(), ""));
    }

    private Product toProduct(Document doc) {
        Long legacyId = toLong(doc.get("legacyId"));
        Product product = new Product(
                safeText(doc.getString("name"), "Unknown Product"),
                safeText(doc.getString("category"), "unknown"),
                toInteger(doc.get("ecoScore")),
                toDouble(doc.get("co2Gram")),
                safeText(doc.getString("recyclability"), "Unknown"),
                safeText(doc.getString("altRecommendation"), ""),
                safeText(doc.getString("explanation"), ""),
                safeText(doc.getString("material"), ""),
                toBoolean(doc.get("isReusable")),
                toBoolean(doc.get("isSingleUse")),
                toInteger(doc.get("recycledContentPercent")),
                safeText(doc.getString("lifecycleType"), "")
        );

        // Preserve JPA legacy id when available (best-effort reflection keeps Product API unchanged).
        if (legacyId != null) {
            try {
                var idField = Product.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(product, legacyId);
            } catch (Exception ignored) {
                // Non-fatal: id is optional for runtime reads.
            }
        }
        return product;
    }

    private Document toHistoryDocument(ScanHistoryEntry entry) {
        String scannedAtIso = entry.getScannedAt() == null
                ? LocalDateTime.now(ZoneOffset.UTC).atOffset(ZoneOffset.UTC).toInstant().toString()
                : entry.getScannedAt().atOffset(ZoneOffset.UTC).toInstant().toString();

        return new Document("legacyId", entry.getId())
                .append("userId", safeText(entry.getUserId(), ""))
                .append("item", safeText(entry.getItemName(), "Unknown item"))
                .append("category", safeText(entry.getCategory(), "unknown"))
                .append("ecoScore", entry.getEcoScore() == null ? 0 : entry.getEcoScore())
                .append("confidence", entry.getConfidence() == null ? 0.0 : entry.getConfidence())
                .append("scannedAt", scannedAtIso);
    }

    private ScanHistoryEntry toHistoryEntry(Document doc) {
        String scannedAtRaw = safeText(doc.getString("scannedAt"), "");
        LocalDateTime scannedAt;
        if (scannedAtRaw.isBlank()) {
            scannedAt = LocalDateTime.now(ZoneOffset.UTC);
        } else {
            try {
                scannedAt = LocalDateTime.ofInstant(java.time.Instant.parse(scannedAtRaw), ZoneOffset.UTC);
            } catch (DateTimeParseException ex) {
                scannedAt = LocalDateTime.now(ZoneOffset.UTC);
            }
        }

        ScanHistoryEntry entry = new ScanHistoryEntry(
                safeText(doc.getString("userId"), ""),
                safeText(doc.getString("item"), "Unknown item"),
                safeText(doc.getString("category"), "unknown"),
                toInteger(doc.get("ecoScore")) == null ? 0 : toInteger(doc.get("ecoScore")),
                toDouble(doc.get("confidence")) == null ? 0.0 : toDouble(doc.get("confidence")),
                scannedAt
        );

        Long legacyId = toLong(doc.get("legacyId"));
        if (legacyId != null) {
            try {
                var idField = ScanHistoryEntry.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(entry, legacyId);
            } catch (Exception ignored) {
                // Non-fatal: id is optional for responses.
            }
        }

        return entry;
    }

    private String normalizeKey(String value) {
        return safeText(value, "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", "_");
    }

    private String safeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    @FunctionalInterface
    private interface MongoCollectionFunction<T> {
        T apply(MongoCollection<Document> collection);
    }
}
