package com.ecolens.ecolens_backend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.ecolens.ecolens_backend.service.MongoAtlasMigrationService;

@Component
public class MongoAtlasStartupRunner implements ApplicationRunner {

    private final MongoAtlasMigrationService mongoAtlasMigrationService;

    public MongoAtlasStartupRunner(MongoAtlasMigrationService mongoAtlasMigrationService) {
        this.mongoAtlasMigrationService = mongoAtlasMigrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        mongoAtlasMigrationService.migrateOnStartupIfConfigured();
    }
}
