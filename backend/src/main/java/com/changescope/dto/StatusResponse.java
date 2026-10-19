package com.changescope.dto;

import java.time.Instant;

public record StatusResponse(
    String application,
    String status,
    String version,
    Instant timestamp
) {}
