package com.changescope.model;

/**
 * Status lifecycle of a persistent repository index snapshot.
 */
public enum RepositoryIndexStatus {
    PENDING,
    INDEXING,
    COMPLETED,
    PARTIAL,
    FAILED
}
