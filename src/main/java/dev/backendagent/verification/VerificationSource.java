package dev.backendagent.verification;

import dev.backendagent.model.FileFingerprint;

/** A source actually visible outside the basis area; IDs are stable across projections. */
public record VerificationSource(String id, String kind, int turnId, String content,
                                 FileFingerprint file, String currency) {
    public VerificationSource {
        if (id == null || id.isBlank() || kind == null || turnId < 0 || content == null
                || content.isBlank() || currency == null) throw new IllegalArgumentException("Invalid basis source");
    }
    public boolean userRequirement() { return kind.equals("USER"); }
}
