package com.changescope.service;

import com.changescope.dto.AffectedMethod;
import com.changescope.dto.AffectedType;
import com.changescope.dto.AnalysisConfidence;
import com.changescope.dto.ConfidenceLevel;
import com.changescope.dto.FileParseError;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.MethodDependency;
import com.changescope.dto.TestImpactCandidate;
import com.changescope.dto.TestMatchEvidence;
import com.changescope.dto.TypeDependency;
import com.changescope.dto.UnresolvedMethodCall;
import com.changescope.dto.VerificationPlan;
import com.changescope.dto.VerificationPriority;
import com.changescope.dto.VerificationRecommendation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VerificationPlanService {

    public VerificationPlan generatePlan(ImpactReport report) {
        if (report == null) {
            return generatePlan(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of()
            );
        }

        return generatePlan(
            report.changedTypes(),
            report.affectedTypes(),
            report.dependencies(),
            report.parseErrors(),
            report.changedMethods(),
            report.affectedMethods(),
            report.methodDependencies(),
            report.unresolvedMethodCalls(),
            report.candidateTests()
        );
    }

    public VerificationPlan generatePlan(
        List<String> changedTypes,
        List<AffectedType> affectedTypes,
        List<TypeDependency> dependencies,
        List<FileParseError> parseErrors,
        List<String> changedMethods,
        List<AffectedMethod> affectedMethods,
        List<MethodDependency> methodDependencies,
        List<UnresolvedMethodCall> unresolvedMethodCalls,
        List<TestImpactCandidate> candidateTests
    ) {
        List<String> safeChangedTypes = changedTypes != null ? changedTypes : List.of();
        List<AffectedType> safeAffectedTypes = affectedTypes != null ? affectedTypes : List.of();
        List<FileParseError> safeParseErrors = parseErrors != null ? parseErrors : List.of();
        List<AffectedMethod> safeAffectedMethods = affectedMethods != null ? affectedMethods : List.of();
        List<UnresolvedMethodCall> safeUnresolvedCalls = unresolvedMethodCalls != null ? unresolvedMethodCalls : List.of();
        List<TestImpactCandidate> safeCandidateTests = candidateTests != null ? candidateTests : List.of();

        // 1. Calculate Confidence
        AnalysisConfidence confidence = calculateConfidence(
            safeChangedTypes,
            safeAffectedTypes,
            safeParseErrors,
            safeUnresolvedCalls,
            safeCandidateTests
        );

        // 2. Build Recommendations
        List<VerificationRecommendation> recommendations = new ArrayList<>();

        // A. Parse Errors (Critical/High Priority)
        for (FileParseError err : safeParseErrors) {
            recommendations.add(new VerificationRecommendation(
                VerificationPriority.HIGH,
                "PARSE_ERROR",
                err.filePath(),
                "Fix syntax error in '" + err.filePath() + "' to enable complete AST inspection",
                "Source parsing failed: " + err.errorMessage()
            ));
        }

        // B. Ambiguous Overloads & Unresolved Calls
        for (UnresolvedMethodCall call : safeUnresolvedCalls) {
            if ("AMBIGUOUS_OVERLOAD".equals(call.reason())) {
                recommendations.add(new VerificationRecommendation(
                    VerificationPriority.HIGH,
                    "AMBIGUOUS_CALL",
                    call.callerType() + "#" + call.calledMethodName(),
                    "Disambiguate overloaded call to '" + call.calledMethodName() + "' in '" + call.callerType() + "'",
                    "Matches multiple candidate methods with identical arity: " + String.join(", ", call.candidateMethods())
                ));
            } else if ("UNRESOLVED_TARGET_TYPE".equals(call.reason())) {
                recommendations.add(new VerificationRecommendation(
                    VerificationPriority.LOW,
                    "UNRESOLVED_CALL",
                    call.callerType() + " -> " + call.calledMethodName(),
                    "Review external invocation of '" + call.calledMethodName() + "'",
                    "Target receiver type '" + (call.targetType() != null ? call.targetType() : "unknown") + "' is external to analyzed files"
                ));
            } else {
                recommendations.add(new VerificationRecommendation(
                    VerificationPriority.MEDIUM,
                    "UNRESOLVED_CALL",
                    call.callerType() + " -> " + call.calledMethodName(),
                    "Review unresolved call to '" + call.calledMethodName() + "' in '" + call.callerType() + "'",
                    "Call could not be resolved: " + call.reason()
                ));
            }
        }

        // C. Candidate Tests
        for (TestImpactCandidate test : safeCandidateTests) {
            String testTarget = test.testClassName() + (test.testMethodName() != null ? "#" + test.testMethodName() : "");
            if (test.evidenceKind() == TestMatchEvidence.METHOD_INVOCATION) {
                recommendations.add(new VerificationRecommendation(
                    VerificationPriority.HIGH,
                    "CANDIDATE_TEST",
                    testTarget,
                    "Run targeted test method '" + test.testMethodName() + "'",
                    test.reason()
                ));
            } else if (test.evidenceKind() == TestMatchEvidence.TYPE_REFERENCE) {
                recommendations.add(new VerificationRecommendation(
                    VerificationPriority.MEDIUM,
                    "CANDIDATE_TEST",
                    testTarget,
                    "Execute test method '" + test.testMethodName() + "'",
                    test.reason()
                ));
            } else {
                recommendations.add(new VerificationRecommendation(
                    VerificationPriority.LOW,
                    "CANDIDATE_TEST",
                    testTarget,
                    "Run regression test suite in '" + test.testClassName() + "'",
                    test.reason()
                ));
            }
        }

        // D. Affected Methods lacking direct test invocation
        Set<String> directlyTestedMethods = safeCandidateTests.stream()
            .filter(c -> c.evidenceKind() == TestMatchEvidence.METHOD_INVOCATION)
            .map(TestImpactCandidate::targetElement)
            .collect(Collectors.toSet());

        for (AffectedMethod affectedMethod : safeAffectedMethods) {
            if (!directlyTestedMethods.contains(affectedMethod.methodIdentifier())) {
                VerificationPriority priority = (affectedMethod.distance() == 1)
                    ? VerificationPriority.HIGH
                    : VerificationPriority.MEDIUM;
                recommendations.add(new VerificationRecommendation(
                    priority,
                    "AFFECTED_METHOD",
                    affectedMethod.methodIdentifier(),
                    "Verify affected method in '" + affectedMethod.declaringType() + "'",
                    "Method is in blast radius (distance " + affectedMethod.distance() + ") but lacks direct test invocation evidence."
                ));
            }
        }

        // E. Direct Dependent Types (Distance 1)
        for (AffectedType affectedType : safeAffectedTypes) {
            if (affectedType.distance() == 1) {
                recommendations.add(new VerificationRecommendation(
                    VerificationPriority.MEDIUM,
                    "AFFECTED_TYPE",
                    affectedType.typeName(),
                    "Review direct dependent component '" + affectedType.typeName() + "'",
                    "Direct dependency (distance 1) on changed type; verify interface contracts and runtime behavior."
                ));
            }
        }

        // Sort deterministically: Priority -> Category -> Target
        recommendations.sort(Comparator
            .comparing(VerificationRecommendation::priority)
            .thenComparing(VerificationRecommendation::category)
            .thenComparing(VerificationRecommendation::target));

        return new VerificationPlan(confidence, recommendations);
    }

    private AnalysisConfidence calculateConfidence(
        List<String> changedTypes,
        List<AffectedType> affectedTypes,
        List<FileParseError> parseErrors,
        List<UnresolvedMethodCall> unresolvedCalls,
        List<TestImpactCandidate> candidateTests
    ) {
        int score = 100;
        List<String> positiveFactors = new ArrayList<>();
        List<String> negativeFactors = new ArrayList<>();

        if (changedTypes.isEmpty() && parseErrors.isEmpty() && unresolvedCalls.isEmpty()) {
            positiveFactors.add("No changed types or files submitted; baseline analysis state.");
            return new AnalysisConfidence(ConfidenceLevel.HIGH, 100, "HIGH_CONFIDENCE", positiveFactors, negativeFactors);
        }

        // 1. Parse errors deduction
        if (!parseErrors.isEmpty()) {
            int deduction = Math.min(50, parseErrors.size() * 25);
            score -= deduction;
            negativeFactors.add(String.format(
                "%d source file(s) failed to parse, preventing full AST traversal and complete impact detection.",
                parseErrors.size()
            ));
        } else {
            positiveFactors.add("All supplied source files parsed successfully without AST syntax errors.");
        }

        // 2. Ambiguous overloads deduction
        long ambiguousCount = unresolvedCalls.stream()
            .filter(c -> "AMBIGUOUS_OVERLOAD".equals(c.reason()))
            .count();
        if (ambiguousCount > 0) {
            int deduction = (int) Math.min(30, ambiguousCount * 15);
            score -= deduction;
            negativeFactors.add(String.format(
                "%d method call(s) have ambiguous overloads with identical parameter counts.",
                ambiguousCount
            ));
        }

        // 3. Other unresolved calls deduction
        long otherUnresolvedCount = unresolvedCalls.size() - ambiguousCount;
        if (otherUnresolvedCount > 0) {
            int deduction = (int) Math.min(20, otherUnresolvedCount * 5);
            score -= deduction;
            negativeFactors.add(String.format(
                "%d method call(s) target external or unresolvable types.",
                otherUnresolvedCount
            ));
        }

        if (ambiguousCount == 0 && otherUnresolvedCount == 0) {
            positiveFactors.add("All static method invocations across supplied files were fully resolved with zero ambiguities.");
        }

        // 4. Test coverage factor
        boolean hasDirectTest = candidateTests.stream().anyMatch(TestImpactCandidate::isDirectEvidence);
        if (hasDirectTest) {
            positiveFactors.add("Identified candidate test(s) with direct static execution or type reference evidence.");
        } else if (!changedTypes.isEmpty() && candidateTests.isEmpty()) {
            score -= 10;
            negativeFactors.add("No candidate tests were identified for the changed production components.");
        }

        int finalScore = Math.max(0, Math.min(100, score));
        ConfidenceLevel level;
        String rating;

        if (finalScore >= 80) {
            level = ConfidenceLevel.HIGH;
            rating = "HIGH_CONFIDENCE";
        } else if (finalScore >= 50) {
            level = ConfidenceLevel.MEDIUM;
            rating = "MEDIUM_CONFIDENCE";
        } else {
            level = ConfidenceLevel.LOW;
            rating = "LOW_CONFIDENCE";
        }

        return new AnalysisConfidence(level, finalScore, rating, positiveFactors, negativeFactors);
    }
}
