package com.changescope.dto;

public record FileParseError(
    String filePath,
    String errorMessage
) {}
