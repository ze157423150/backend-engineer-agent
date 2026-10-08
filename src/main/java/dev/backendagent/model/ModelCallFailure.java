package dev.backendagent.model;

/** Application-owned message and typed diagnostic; deliberately excludes raw causes. */
public final class ModelCallFailure extends IllegalStateException {
    private final ModelFailureDiagnostic diagnostic;
    public ModelCallFailure(String message, ModelFailureDiagnostic diagnostic) {
        super(message);
        this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
    }
    public ModelFailureDiagnostic getDiagnostic() { return diagnostic; }
}
