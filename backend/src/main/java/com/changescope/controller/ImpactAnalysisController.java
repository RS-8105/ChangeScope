package com.changescope.controller;

import com.changescope.dto.ImpactAnalysisRequest;
import com.changescope.dto.ImpactReport;
import com.changescope.service.ImpactReportService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
public class ImpactAnalysisController {

    private final ImpactReportService impactReportService;

    public ImpactAnalysisController(ImpactReportService impactReportService) {
        this.impactReportService = impactReportService;
    }

    @PostMapping("/impact")
    public ResponseEntity<ImpactReport> analyzeImpact(@Valid @RequestBody ImpactAnalysisRequest request) {
        ImpactReport report = impactReportService.generateReport(
            request.toFileInputs(),
            request.changedTypes()
        );
        return ResponseEntity.ok(report);
    }
}
