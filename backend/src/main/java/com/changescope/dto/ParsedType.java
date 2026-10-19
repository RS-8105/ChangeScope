package com.changescope.dto;

import java.util.List;

public record ParsedType(
    String name,
    TypeKind kind,
    List<String> extendedTypes,
    List<String> implementedTypes,
    List<ParsedField> fields,
    List<ParsedMethod> methods,
    List<ParsedConstructor> constructors
) {
    public ParsedType {
        extendedTypes = extendedTypes != null ? List.copyOf(extendedTypes) : List.of();
        implementedTypes = implementedTypes != null ? List.copyOf(implementedTypes) : List.of();
        fields = fields != null ? List.copyOf(fields) : List.of();
        methods = methods != null ? List.copyOf(methods) : List.of();
        constructors = constructors != null ? List.copyOf(constructors) : List.of();
    }

    public ParsedType(String name, TypeKind kind, List<ParsedMethod> methods) {
        this(name, kind, List.of(), List.of(), List.of(), methods, List.of());
    }

    public boolean isInterface() {
        return kind == TypeKind.INTERFACE;
    }

    public boolean isClass() {
        return kind == TypeKind.CLASS;
    }
}
