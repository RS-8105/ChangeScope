package com.changescope.dto;

import java.util.List;

public record ParsedJavaFile(
    boolean success,
    String errorMessage,
    String packageName,
    List<String> imports,
    List<ParsedType> types
) {
    public ParsedJavaFile {
        imports = imports != null ? List.copyOf(imports) : List.of();
        types = types != null ? List.copyOf(types) : List.of();
    }

    public static ParsedJavaFile success(String packageName, List<String> imports, List<ParsedType> types) {
        return new ParsedJavaFile(true, null, packageName, imports, types);
    }

    public static ParsedJavaFile error(String errorMessage) {
        return new ParsedJavaFile(false, errorMessage, null, List.of(), List.of());
    }
}
