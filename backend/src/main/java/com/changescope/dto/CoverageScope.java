package com.changescope.dto;

/**
 * Defines the repository boundary and scope of the impact analysis.
 */
public enum CoverageScope {
    /**
     * Analysis is strictly limited to files present in the pull request changeset.
     */
    PR_FILES_ONLY,

    /**
     * Analysis includes a bounded subset of unchanged repository files discovered via base tree.
     */
    PARTIAL_INDEX_BOUNDED,

    /**
     * Complete, repository-wide indexed dependency graph.
     */
    FULL_REPOSITORY_INDEX
}
