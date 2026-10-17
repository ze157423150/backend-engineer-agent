package dev.backendagent.verification;

import java.util.List;

/** Durable last automatic update; file currency and visibility are projected afresh. */
public record VerificationLedger(int turnId, String attemptedDigest, List<ActiveVerificationBasis> bases,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.Map<String,String> attemptedFileVersions) {
    public static final int MAX_CHARACTERS = 6000;
    public static final int COMPACT_MAX_CHARACTERS = 3999;

    /** Actual model-facing JSON, including source/status/path metadata, without audit-only quotations. */
    public static int displayCharacters(List<ActiveVerificationBasis> bases) {
        return VerificationState.encode(bases.stream().map(ActiveVerificationBasis::contextData).toList()).length();
    }

    /** Backward-compatible constructor for older checkpoints and callers. */
    public VerificationLedger(int turnId,String attemptedDigest,List<ActiveVerificationBasis> bases) {
        this(turnId,attemptedDigest,bases,java.util.Map.of());
    }

    public VerificationLedger {
        bases = List.copyOf(bases);
        attemptedFileVersions=attemptedFileVersions==null?java.util.Map.of():java.util.Map.copyOf(attemptedFileVersions);
        if(attemptedFileVersions.entrySet().stream().anyMatch(e->e.getKey().isBlank()||!e.getValue().matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid attempted verification file version");
        if (turnId < 1 || attemptedDigest == null || !attemptedDigest.matches("[0-9a-f]{64}")
                // Retain the legacy storage guard for existing checkpoints. New updates additionally
                // enforce MAX_CHARACTERS against their serialized display in VerificationUpdateRequest.
                || bases.stream().map(b -> b.contextData().toString()).mapToInt(String::length).sum() > MAX_CHARACTERS)
            throw new IllegalArgumentException("Verification ledger exceeds budget");
    }
}
