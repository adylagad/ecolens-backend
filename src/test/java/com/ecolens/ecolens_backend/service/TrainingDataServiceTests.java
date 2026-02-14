package com.ecolens.ecolens_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.ecolens.ecolens_backend.dto.TrainingExportResponse;
import com.ecolens.ecolens_backend.dto.TrainingSampleRequest;
import com.ecolens.ecolens_backend.dto.TrainingSampleResponse;
import com.ecolens.ecolens_backend.repository.TrainingSampleRepository;

@SpringBootTest
class TrainingDataServiceTests {

    @Autowired
    private TrainingDataService trainingDataService;

    @Autowired
    private TrainingSampleRepository trainingSampleRepository;

    @BeforeEach
    void cleanTable() {
        trainingSampleRepository.deleteAll();
    }

    @Test
    void saveSampleResolvesHydroFlaskToReusableBottle() {
        TrainingSampleRequest request = new TrainingSampleRequest();
        request.setUserId("test-user");
        request.setPredictedLabel("water bottle");
        request.setPredictedConfidence(0.14);
        request.setFinalLabel("Hydro Flask");
        request.setImageBase64("aGVsbG8=");

        TrainingSampleResponse saved = trainingDataService.saveSample(request);

        assertNotNull(saved.getId());
        assertEquals("Hydro Flask", saved.getFinalLabel());
        assertEquals("reusable_bottle", saved.getTaxonomyLeaf());
        assertEquals("hydration_drinkware", saved.getTaxonomyParent());
        assertNotNull(saved.getImageSha256());
    }

    @Test
    void exportIncludesSavedSamples() {
        TrainingSampleRequest request = new TrainingSampleRequest();
        request.setUserId("tester");
        request.setFinalLabel("Laptop Charger");
        request.setImageBase64("aGVsbG8=");

        trainingDataService.saveSample(request);

        TrainingExportResponse export = trainingDataService.exportSamples(100, true, true);
        assertEquals("ecolens-taxonomy-v1", export.getTaxonomyVersion());
        assertTrue(export.getSampleCount() >= 1);
        assertTrue(export.getSamples().stream().anyMatch(sample -> "Laptop Charger".equals(sample.getFinalLabel())));
    }
}
