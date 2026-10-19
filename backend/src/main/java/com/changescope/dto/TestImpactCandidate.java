package com.changescope.dto;

public record TestImpactCandidate(
    String testFilePath,
    String testClassName,
    String testMethodName,
    TestMatchEvidence evidenceKind,
    String reason,
    String targetElement
) {
    public boolean isDirectEvidence() {
        return evidenceKind == TestMatchEvidence.METHOD_INVOCATION 
            || evidenceKind == TestMatchEvidence.TYPE_REFERENCE;
    }
}
