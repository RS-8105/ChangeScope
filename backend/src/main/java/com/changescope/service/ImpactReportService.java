package com.changescope.service;

import com.changescope.dto.BlastRadiusResult;
import com.changescope.dto.DependencyAnalysisResult;
import com.changescope.dto.FileParseError;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.MethodAnalysisResult;
import com.changescope.dto.TestImpactResult;
import com.changescope.dto.VerificationPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ImpactReportService {

    private final JavaDependencyAnalysisService dependencyAnalysisService;
    private final BlastRadiusAnalysisService blastRadiusAnalysisService;
    private final JavaMethodAnalysisService javaMethodAnalysisService;
    private final TestImpactAnalysisService testImpactAnalysisService;
    private final VerificationPlanService verificationPlanService;

    public ImpactReportService(
        JavaDependencyAnalysisService dependencyAnalysisService,
        BlastRadiusAnalysisService blastRadiusAnalysisService
    ) {
        this(dependencyAnalysisService, blastRadiusAnalysisService, new JavaMethodAnalysisService(), new TestImpactAnalysisService(), new VerificationPlanService());
    }

    public ImpactReportService(
        JavaDependencyAnalysisService dependencyAnalysisService,
        BlastRadiusAnalysisService blastRadiusAnalysisService,
        JavaMethodAnalysisService javaMethodAnalysisService
    ) {
        this(dependencyAnalysisService, blastRadiusAnalysisService, javaMethodAnalysisService, new TestImpactAnalysisService(), new VerificationPlanService());
    }

    public ImpactReportService(
        JavaDependencyAnalysisService dependencyAnalysisService,
        BlastRadiusAnalysisService blastRadiusAnalysisService,
        JavaMethodAnalysisService javaMethodAnalysisService,
        TestImpactAnalysisService testImpactAnalysisService
    ) {
        this(dependencyAnalysisService, blastRadiusAnalysisService, javaMethodAnalysisService, testImpactAnalysisService, new VerificationPlanService());
    }

    public ImpactReportService(
        JavaDependencyAnalysisService dependencyAnalysisService,
        BlastRadiusAnalysisService blastRadiusAnalysisService,
        JavaMethodAnalysisService javaMethodAnalysisService,
        TestImpactAnalysisService testImpactAnalysisService,
        VerificationPlanService verificationPlanService
    ) {
        this.dependencyAnalysisService = dependencyAnalysisService;
        this.blastRadiusAnalysisService = blastRadiusAnalysisService;
        this.javaMethodAnalysisService = javaMethodAnalysisService;
        this.testImpactAnalysisService = testImpactAnalysisService;
        this.verificationPlanService = verificationPlanService;
    }

    public ImpactReport generateReport(List<JavaSourceFileInput> files, List<String> changedTypes) {
        DependencyAnalysisResult dependencyResult = dependencyAnalysisService.analyze(files);
        BlastRadiusResult blastRadiusResult = blastRadiusAnalysisService.analyze(dependencyResult, changedTypes);
        MethodAnalysisResult methodResult = javaMethodAnalysisService.analyze(files, changedTypes);
        TestImpactResult testImpactResult = testImpactAnalysisService.analyze(files, blastRadiusResult, methodResult);

        List<FileParseError> allErrors = new ArrayList<>(dependencyResult.errors());
        for (FileParseError err : testImpactResult.parseErrors()) {
            if (allErrors.stream().noneMatch(e -> e.filePath().equals(err.filePath()))) {
                allErrors.add(err);
            }
        }

        VerificationPlan verificationPlan = verificationPlanService.generatePlan(
            blastRadiusResult.changedTypes(),
            blastRadiusResult.affectedTypes(),
            dependencyResult.dependencies(),
            allErrors,
            methodResult.changedMethods(),
            methodResult.affectedMethods(),
            methodResult.methodDependencies(),
            methodResult.unresolvedCalls(),
            testImpactResult.candidateTests()
        );

        return new ImpactReport(
            blastRadiusResult.changedTypes(),
            blastRadiusResult.affectedTypes(),
            dependencyResult.dependencies(),
            allErrors,
            methodResult.changedMethods(),
            methodResult.affectedMethods(),
            methodResult.methodDependencies(),
            methodResult.unresolvedCalls(),
            testImpactResult.candidateTests(),
            verificationPlan
        );
    }

    public ImpactReport generateReport(Map<String, String> filesByPath, List<String> changedTypes) {
        if (filesByPath == null || filesByPath.isEmpty()) {
            return generateReport(List.of(), changedTypes);
        }
        List<JavaSourceFileInput> inputs = filesByPath.entrySet().stream()
            .map(entry -> new JavaSourceFileInput(entry.getKey(), entry.getValue()))
            .toList();
        return generateReport(inputs, changedTypes);
    }

    public ImpactReport generateReport(List<JavaSourceFileInput> files, String... changedTypes) {
        List<String> list = changedTypes != null ? List.of(changedTypes) : List.of();
        return generateReport(files, list);
    }
}
