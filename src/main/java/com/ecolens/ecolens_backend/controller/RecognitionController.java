package com.ecolens.ecolens_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecolens.ecolens_backend.dto.RecognitionRequest;
import com.ecolens.ecolens_backend.dto.RecognitionResponse;
import com.ecolens.ecolens_backend.service.ProductService;

@RestController
@RequestMapping("/api")
public class RecognitionController {

    private final ProductService productService;

    public RecognitionController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/recognize")
    public ResponseEntity<RecognitionResponse> recognize(@RequestBody RecognitionRequest request) {
        double confidence = request.getConfidence() == null ? 0.0 : request.getConfidence();
        RecognitionResponse response = productService.handleRecognition(request.getDetectedLabel(), confidence);
        return ResponseEntity.ok(response);
    }
}
