package com.changescope.service;

import com.changescope.dto.StatusResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class StatusService {

    private static final String APP_NAME = "ChangeScope";
    private static final String APP_STATUS = "UP";
    private static final String APP_VERSION = "0.0.1-SNAPSHOT";

    public StatusResponse getStatus() {
        return new StatusResponse(APP_NAME, APP_STATUS, APP_VERSION, Instant.now());
    }
}
