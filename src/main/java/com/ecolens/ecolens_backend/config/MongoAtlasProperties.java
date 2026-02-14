package com.ecolens.ecolens_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mongodb.atlas")
public class MongoAtlasProperties {

    private String uri = "";
    private String database = "ecolens";
    private String productsCollection = "products";
    private String historyCollection = "scan_history";
    private boolean migrationEnabled = false;
    private boolean runtimeEnabled = false;
    private boolean runOnStartup = false;
    private boolean migrateProducts = true;
    private boolean migrateHistory = true;
    private int connectTimeoutMs = 4000;
    private int socketTimeoutMs = 5000;
    private int serverSelectionTimeoutMs = 4000;

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getProductsCollection() {
        return productsCollection;
    }

    public void setProductsCollection(String productsCollection) {
        this.productsCollection = productsCollection;
    }

    public String getHistoryCollection() {
        return historyCollection;
    }

    public void setHistoryCollection(String historyCollection) {
        this.historyCollection = historyCollection;
    }

    public boolean isMigrationEnabled() {
        return migrationEnabled;
    }

    public void setMigrationEnabled(boolean migrationEnabled) {
        this.migrationEnabled = migrationEnabled;
    }

    public boolean isRuntimeEnabled() {
        return runtimeEnabled;
    }

    public void setRuntimeEnabled(boolean runtimeEnabled) {
        this.runtimeEnabled = runtimeEnabled;
    }

    public boolean isRunOnStartup() {
        return runOnStartup;
    }

    public void setRunOnStartup(boolean runOnStartup) {
        this.runOnStartup = runOnStartup;
    }

    public boolean isMigrateProducts() {
        return migrateProducts;
    }

    public void setMigrateProducts(boolean migrateProducts) {
        this.migrateProducts = migrateProducts;
    }

    public boolean isMigrateHistory() {
        return migrateHistory;
    }

    public void setMigrateHistory(boolean migrateHistory) {
        this.migrateHistory = migrateHistory;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public int getServerSelectionTimeoutMs() {
        return serverSelectionTimeoutMs;
    }

    public void setServerSelectionTimeoutMs(int serverSelectionTimeoutMs) {
        this.serverSelectionTimeoutMs = serverSelectionTimeoutMs;
    }
}
