package com.changescope.dto;

import java.util.List;

public record BlastRadiusResult(
    List<String> changedTypes,
    List<AffectedType> affectedTypes
) {
    public BlastRadiusResult {
        changedTypes = changedTypes != null ? List.copyOf(changedTypes) : List.of();
        affectedTypes = affectedTypes != null ? List.copyOf(affectedTypes) : List.of();
    }

    public List<String> affectedTypeNames() {
        return affectedTypes.stream().map(AffectedType::typeName).toList();
    }
}
