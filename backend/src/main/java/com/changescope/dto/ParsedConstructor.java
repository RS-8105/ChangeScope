package com.changescope.dto;

import java.util.List;

public record ParsedConstructor(
    String name,
    List<String> parameterTypes,
    List<ParsedParameter> parameters,
    List<String> referencedTypeNames
) {
    public ParsedConstructor {
        parameterTypes = parameterTypes != null ? List.copyOf(parameterTypes) : List.of();
        parameters = parameters != null ? List.copyOf(parameters) : List.of();
        referencedTypeNames = referencedTypeNames != null ? List.copyOf(referencedTypeNames) : List.of();
    }

    public ParsedConstructor(String name, List<String> parameterTypes, List<ParsedParameter> parameters) {
        this(name, parameterTypes, parameters, List.of());
    }
}
