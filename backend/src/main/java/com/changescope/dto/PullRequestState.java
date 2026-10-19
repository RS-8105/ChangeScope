package com.changescope.dto;

public enum PullRequestState {
    OPEN,
    CLOSED,
    MERGED,
    UNKNOWN;

    public static PullRequestState fromString(String state) {
        if (state == null || state.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(state.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
