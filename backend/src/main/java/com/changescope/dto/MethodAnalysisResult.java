package com.changescope.dto;

import java.util.List;

public record MethodAnalysisResult(
    List<String> changedMethods,
    List<AffectedMethod> affectedMethods,
    List<MethodDependency> methodDependencies,
    List<UnresolvedMethodCall> unresolvedCalls
) {
    public MethodAnalysisResult {
        changedMethods = changedMethods != null ? List.copyOf(changedMethods) : List.of();
        affectedMethods = affectedMethods != null ? List.copyOf(affectedMethods) : List.of();
        methodDependencies = methodDependencies != null ? List.copyOf(methodDependencies) : List.of();
        unresolvedCalls = unresolvedCalls != null ? List.copyOf(unresolvedCalls) : List.of();
    }
}
