package com.changescope.dto;

public enum FileChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    UNKNOWN;

    public static FileChangeStatus fromString(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String clean = status.trim().toUpperCase();
        if ("REMOVED".equals(clean)) {
            return DELETED;
        }
        if ("CHANGED".equals(clean)) {
            return MODIFIED;
        }
        try {
            return valueOf(clean);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
