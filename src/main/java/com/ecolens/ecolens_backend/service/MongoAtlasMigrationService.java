package com.ecolens.ecolens_backend.service;

import static com.mongodb.client.model.Filters.eq;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.config.MongoAtlasProperties;
import com.ecolens.ecolens_backend.model.Product;
import com.ecolens.ecolens_backend.model.ScanHistoryEntry;
import com.ecolens.ecolens_backend.repository.ProductRepository;
import com.ecolens.ecolens_backend.repository.ScanHistoryRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;

@Service
public class MongoAtlasMigrationService {

    private static final Logger log = LoggerFactory.getLogger(MongoAtlasMigrationService.class);

    private final MongoAtlasProperties mongoAtlasProperties;
    private final ProductRepository productRepository;
    private final ScanHistoryRepository scanHistoryRepository;

    public MongoAtlasMigrationService(
            MongoAtlasProperties mongoAtlasProperties,
            ProductRepository productRepository,
            ScanHistoryRepository scanHistoryRepository
    ) {
        this.mongoAtlasProperties = mongoAtlasProperties;
        this.productRepository = productRepository;
        this.scanHistoryRepository = scanHistoryRepository;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("migrationEnabled", mongoAtlasProperties.isMigrationEnabled());
        out.put("runtimeEnabled", mongoAtlasProperties.isRuntimeEnabled());
        out.put("runOnStartup", mongoAtlasProperties.isRunOnStartup());
        out.put("migrateProducts", mongoAtlasProperties.isMigrateProducts());
        out.put("migrateHistory", mongoAtlasProperties.isMigrateHistory());
        out.put("database", safeText(mongoAtlasProperties.getDatabase(), "ecolens"));
        out.put("productsCollection", safeText(mongoAtlasProperties.getProductsCollection(), "products"));
        out.put("historyCollection", safeText(mongoAtlasProperties.getHistoryCollection(), "scan_history"));
        out.put("atlasUriConfigured", !safeText(mongoAtlasProperties.getUri(), "").isBlank());
        out.put("sourceProductCount", productRepository.count());
        out.put("sourceHistoryCount", scanHistoryRepository.count());

        if (!safeText(mongoAtlasProperties.getUri(), "").isBlank()) {
            out.put("atlasPing", pingAtlas());
        } else {
            out.put("atlasPing", "skipped_no_uri");
        }

        return out;
    }

    public Map<String, Object> migrateNow() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!mongoAtlasProperties.isMigrationEnabled()) {
            out.put("ok", false);
            out.put("message", "MongoDB Atlas migration is disabled. Set mongodb.atlas.migration-enabled=true.");
            return out;
        }

        String uri = safeText(mongoAtlasProperties.getUri(), "");
        if (uri.isBlank()) {
            out.put("ok", false);
            out.put("message", "MongoDB Atlas URI is not configured. Set mongodb.atlas.uri.");
            return out;
        }

        long productUpserts = 0;
        long historyUpserts = 0;

        try (MongoClient client = MongoClients.create(uri)) {
            String dbName = safeText(mongoAtlasProperties.getDatabase(), "ecolens");
            MongoDatabase db = client.getDatabase(dbName);

            if (mongoAtlasProperties.isMigrateProducts()) {
                MongoCollection<Document> products = db.getCollection(
                        safeText(mongoAtlasProperties.getProductsCollection(), "products"));
                List<Product> allProducts = productRepository.findAll();
                for (Product product : allProducts) {
                    Document doc = new Document("legacyId", product.getId())
                            .append("name", safeText(product.getName(), ""))
                            .append("category", safeText(product.getCategory(), ""))
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

                    products.replaceOne(eq("legacyId", product.getId()), doc, new ReplaceOptions().upsert(true));
                    productUpserts += 1;
                }
            }

            if (mongoAtlasProperties.isMigrateHistory()) {
                MongoCollection<Document> history = db.getCollection(
                        safeText(mongoAtlasProperties.getHistoryCollection(), "scan_history"));
                List<ScanHistoryEntry> allHistory = scanHistoryRepository.findAll();
                for (ScanHistoryEntry entry : allHistory) {
                    String scannedAtIso = entry.getScannedAt() == null
                            ? null
                            : entry.getScannedAt().atOffset(ZoneOffset.UTC).toInstant().toString();
                    Document doc = new Document("legacyId", entry.getId())
                            .append("userId", safeText(entry.getUserId(), ""))
                            .append("item", safeText(entry.getItemName(), ""))
                            .append("category", safeText(entry.getCategory(), ""))
                            .append("ecoScore", entry.getEcoScore())
                            .append("confidence", entry.getConfidence())
                            .append("scannedAt", scannedAtIso);

                    history.replaceOne(eq("legacyId", entry.getId()), doc, new ReplaceOptions().upsert(true));
                    historyUpserts += 1;
                }
            }

            out.put("ok", true);
            out.put("database", dbName);
            out.put("productsCollection", safeText(mongoAtlasProperties.getProductsCollection(), "products"));
            out.put("historyCollection", safeText(mongoAtlasProperties.getHistoryCollection(), "scan_history"));
            out.put("productsUpserted", productUpserts);
            out.put("historyUpserted", historyUpserts);
            out.put("message", "Migration completed.");
            return out;
        } catch (Exception ex) {
            log.error("MongoDB Atlas migration failed: {}", ex.getMessage(), ex);
            out.put("ok", false);
            out.put("productsUpserted", productUpserts);
            out.put("historyUpserted", historyUpserts);
            out.put("message", "Migration failed: " + ex.getMessage());
            return out;
        }
    }

    public void migrateOnStartupIfConfigured() {
        if (!mongoAtlasProperties.isMigrationEnabled() || !mongoAtlasProperties.isRunOnStartup()) {
            return;
        }
        Map<String, Object> result = migrateNow();
        log.info("Mongo Atlas startup migration result={}", result);
    }

    public Map<String, Object> runtimeCheck() {
        Map<String, Object> out = new LinkedHashMap<>();
        String uri = safeText(mongoAtlasProperties.getUri(), "");
        if (uri.isBlank()) {
            out.put("ok", false);
            out.put("message", "MongoDB Atlas URI is not configured.");
            return out;
        }
        if (!mongoAtlasProperties.isRuntimeEnabled()) {
            out.put("ok", false);
            out.put("message", "Mongo runtime is disabled. Set mongodb.atlas.runtime-enabled=true.");
            return out;
        }

        String dbName = safeText(mongoAtlasProperties.getDatabase(), "ecolens");
        String probeCollectionName = "_runtime_probe";
        String probeId = UUID.randomUUID().toString();

        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(dbName);
            MongoCollection<Document> probeCollection = db.getCollection(probeCollectionName);

            Document probe = new Document("probeId", probeId)
                    .append("createdAt", Instant.now().toString())
                    .append("kind", "runtime-check");

            probeCollection.insertOne(probe);
            Document found = probeCollection.find(eq("probeId", probeId)).first();
            probeCollection.deleteOne(eq("probeId", probeId));

            out.put("ok", found != null);
            out.put("database", dbName);
            out.put("collection", probeCollectionName);
            out.put("probeId", probeId);
            out.put("write", true);
            out.put("read", found != null);
            out.put("delete", true);
            out.put("message", found != null ? "Runtime check passed." : "Runtime check failed: read after write did not return document.");
            return out;
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("database", dbName);
            out.put("collection", probeCollectionName);
            out.put("probeId", probeId);
            out.put("message", "Runtime check failed: " + ex.getMessage());
            return out;
        }
    }

    private String pingAtlas() {
        String uri = safeText(mongoAtlasProperties.getUri(), "");
        if (uri.isBlank()) {
            return "skipped_no_uri";
        }

        try (MongoClient client = MongoClients.create(uri)) {
            client.getDatabase(safeText(mongoAtlasProperties.getDatabase(), "ecolens"))
                    .runCommand(new Document("ping", 1));
            return "ok";
        } catch (Exception ex) {
            return "failed: " + ex.getMessage();
        }
    }

    private String safeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }
}
