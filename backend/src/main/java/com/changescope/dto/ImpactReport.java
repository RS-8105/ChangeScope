package com.changescope.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImpactReport(
    List<String> changedTypes,
    List<AffectedType> affectedTypes,
    int totalChangedTypes,
    int totalAffectedTypes,
    List<TypeDependency> dependencies,
    List<FileParseError> parseErrors,
    List<String> changedMethods,
    List<AffectedMethod> affectedMethods,
    int totalChangedMethods,
    int totalAffectedMethods,
    List<MethodDependency> methodDependencies,
    List<UnresolvedMethodCall> unresolvedMethodCalls,
    List<TestImpactCandidate> candidateTests,
    int totalCandidateTests,
    VerificationPlan verificationPlan
) {
    public ImpactReport {
        changedTypes = changedTypes != null ? List.copyOf(changedTypes) : List.of();
        affectedTypes = affectedTypes != null ? List.copyOf(affectedTypes) : List.of();
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        parseErrors = parseErrors != null ? List.copyOf(parseErrors) : List.of();
        changedMethods = changedMethods != null ? List.copyOf(changedMethods) : List.of();
        affectedMethods = affectedMethods != null ? List.copyOf(affectedMethods) : List.of();
        methodDependencies = methodDependencies != null ? List.copyOf(methodDependencies) : List.of();
        unresolvedMethodCalls = unresolvedMethodCalls != null ? List.copyOf(unresolvedMethodCalls) : List.of();
        candidateTests = candidateTests != null ? List.copyOf(candidateTests) : List.of();
        totalChangedTypes = changedTypes.size();
        totalAffectedTypes = affectedTypes.size();
        totalChangedMethods = changedMethods.size();
        totalAffectedMethods = affectedMethods.size();
        totalCandidateTests = candidateTests.size();
    }

    public ImpactReport(
        List<String> changedTypes,
        List<AffectedType> affectedTypes,
        List<TypeDependency> dependencies,
        List<FileParseError> parseErrors
    ) {
        this(
            changedTypes,
            affectedTypes,
            changedTypes != null ? changedTypes.size() : 0,
            affectedTypes != null ? affectedTypes.size() : 0,
            dependencies,
            parseErrors,
            List.of(),
            List.of(),
            0,
            0,
            List.of(),
            List.of(),
            List.of(),
            0,
            null
        );
    }

    public ImpactReport(
        List<String> changedTypes,
        List<AffectedType> affectedTypes,
        List<TypeDependency> dependencies,
        List<FileParseError> parseErrors,
        List<String> changedMethods,
        List<AffectedMethod> affectedMethods,
        List<MethodDependency> methodDependencies,
        List<UnresolvedMethodCall> unresolvedMethodCalls
    ) {
        this(
            changedTypes,
            affectedTypes,
            changedTypes != null ? changedTypes.size() : 0,
            affectedTypes != null ? affectedTypes.size() : 0,
            dependencies,
            parseErrors,
            changedMethods,
            affectedMethods,
            changedMethods != null ? changedMethods.size() : 0,
            affectedMethods != null ? affectedMethods.size() : 0,
            methodDependencies,
            unresolvedMethodCalls,
            List.of(),
            0,
            null
        );
    }

    public ImpactReport(
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
        this(
            changedTypes,
            affectedTypes,
            dependencies,
            parseErrors,
            changedMethods,
            affectedMethods,
            methodDependencies,
            unresolvedMethodCalls,
            candidateTests,
            null
        );
    }

    public ImpactReport(
        List<String> changedTypes,
        List<AffectedType> affectedTypes,
        List<TypeDependency> dependencies,
        List<FileParseError> parseErrors,
        List<String> changedMethods,
        List<AffectedMethod> affectedMethods,
        List<MethodDependency> methodDependencies,
        List<UnresolvedMethodCall> unresolvedMethodCalls,
        List<TestImpactCandidate> candidateTests,
        VerificationPlan verificationPlan
    ) {
        this(
            changedTypes,
            affectedTypes,
            changedTypes != null ? changedTypes.size() : 0,
            affectedTypes != null ? affectedTypes.size() : 0,
            dependencies,
            parseErrors,
            changedMethods,
            affectedMethods,
            changedMethods != null ? changedMethods.size() : 0,
            affectedMethods != null ? affectedMethods.size() : 0,
            methodDependencies,
            unresolvedMethodCalls,
            candidateTests,
            candidateTests != null ? candidateTests.size() : 0,
            verificationPlan
        );
    }

    @JsonIgnore
    public boolean hasErrors() {
        return !parseErrors.isEmpty();
    }
}
