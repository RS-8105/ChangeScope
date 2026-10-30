package com.changescope.dto;

import java.util.Objects;

public record IndexedTypeDto(
    String fullyQualifiedName,
    String simpleName,
    String packageName,
    String filePath,
    TypeKind typeKind
) {
    public IndexedTypeDto {
        Objects.requireNonNull(fullyQualifiedName, "fullyQualifiedName must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        if (typeKind == null) {
            typeKind = TypeKind.CLASS;
        }
    }

    public IndexedTypeDto(String fullyQualifiedName, String filePath) {
        this(
            fullyQualifiedName,
            extractSimpleName(fullyQualifiedName),
            extractPackageName(fullyQualifiedName),
            filePath,
            TypeKind.CLASS
        );
    }

    public IndexedTypeDto(String fullyQualifiedName, String filePath, TypeKind typeKind) {
        this(
            fullyQualifiedName,
            extractSimpleName(fullyQualifiedName),
            extractPackageName(fullyQualifiedName),
            filePath,
            typeKind != null ? typeKind : TypeKind.CLASS
        );
    }

    private static String extractSimpleName(String fqn) {
        if (fqn == null) return null;
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    private static String extractPackageName(String fqn) {
        if (fqn == null) return null;
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(0, idx) : null;
    }
}
