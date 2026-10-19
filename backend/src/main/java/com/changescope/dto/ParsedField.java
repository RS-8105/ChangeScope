package com.changescope.dto;

import java.util.List;

public record ParsedField(
    String type,
    String name,
    List<String> referencedTypeNames
) {
    public ParsedField {
        referencedTypeNames = referencedTypeNames != null ? List.copyOf(referencedTypeNames) : List.of();
    }

    public ParsedField(String type, String name) {
        this(type, name, List.of());
    }
}
