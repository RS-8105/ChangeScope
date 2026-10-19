package com.changescope.dto;

public record MethodDependency(
    String callerMethod,
    String callerType,
    String targetMethod,
    String targetType,
    String sourceFilePath
) {}
