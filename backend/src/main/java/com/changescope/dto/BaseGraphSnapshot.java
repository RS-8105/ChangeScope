package com.changescope.dto;

import com.changescope.model.IndexedTypeDependencyEntity;
import com.changescope.model.IndexedTypeEntity;

import java.util.List;
import java.util.Objects;

public record BaseGraphSnapshot(
    List<IndexedTypeDto> indexedTypes,
    List<TypeDependency> dependencies
) {
    public BaseGraphSnapshot {
        indexedTypes = indexedTypes != null ? List.copyOf(indexedTypes) : List.of();
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
    }

    public static BaseGraphSnapshot fromEntities(
        List<IndexedTypeEntity> typeEntities,
        List<IndexedTypeDependencyEntity> depEntities
    ) {
        List<IndexedTypeDto> types = typeEntities == null ? List.of() : typeEntities.stream()
            .filter(Objects::nonNull)
            .map(t -> new IndexedTypeDto(
                t.getFullyQualifiedName(),
                t.getSimpleName(),
                t.getPackageName(),
                t.getFilePath(),
                t.getTypeKind()
            ))
            .toList();

        List<TypeDependency> deps = depEntities == null ? List.of() : depEntities.stream()
            .filter(Objects::nonNull)
            .map(d -> new TypeDependency(
                d.getSourceType(),
                d.getTargetType(),
                d.getRelationshipKind(),
                d.getSourceFilePath()
            ))
            .toList();

        return new BaseGraphSnapshot(types, deps);
    }
}
