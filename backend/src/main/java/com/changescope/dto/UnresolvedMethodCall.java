package com.changescope.dto;

import java.util.List;

public record UnresolvedMethodCall(
    String callerMethod,
    String callerType,
    String calledMethodName,
    String targetType,
    String reason,
    List<String> candidateMethods,
    String sourceFilePath
) {
    public UnresolvedMethodCall {
        candidateMethods = candidateMethods != null ? List.copyOf(candidateMethods) : List.of();
    }

    public UnresolvedMethodCall(
        String callerMethod,
        String callerType,
        String calledMethodName,
        String targetType,
        String reason,
        String sourceFilePath
    ) {
        this(callerMethod, callerType, calledMethodName, targetType, reason, List.of(), sourceFilePath);
    }
}
