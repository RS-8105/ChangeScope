package com.changescope.dto;

import java.util.List;

public record ParsedMethod(
    String name,
    String returnType,
    List<String> parameterTypes,
    List<ParsedParameter> parameters,
    List<String> returnTypeReferencedTypes,
    List<String> parameterReferencedTypes
) {
    public ParsedMethod {
        parameterTypes = parameterTypes != null ? List.copyOf(parameterTypes) : List.of();
        parameters = parameters != null ? List.copyOf(parameters) : List.of();
        returnTypeReferencedTypes = returnTypeReferencedTypes != null ? List.copyOf(returnTypeReferencedTypes) : List.of();
        parameterReferencedTypes = parameterReferencedTypes != null ? List.copyOf(parameterReferencedTypes) : List.of();
    }

    public ParsedMethod(String name, String returnType, List<String> parameterTypes, List<ParsedParameter> parameters) {
        this(name, returnType, parameterTypes, parameters, List.of(), List.of());
    }
}
