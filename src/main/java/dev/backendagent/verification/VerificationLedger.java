package dev.backendagent.verification;

import java.util.List;

/** Durable last automatic update; file currency and visibility are projected afresh. */
public record VerificationLedger(int turnId, String attemptedDigest, List<ActiveVerificationBasis> bases) {
    public VerificationLedger {
        bases = List.copyOf(bases);
        if (turnId < 1 || attemptedDigest == null || !attemptedDigest.matches("[0-9a-f]{64}")
                || bases.size() > 12 || bases.stream().map(b -> b.contextData().toString()).mapToInt(String::length).sum() > 6000)
            throw new IllegalArgumentException("Verification ledger exceeds budget");
    }
}
