package dev.backendagent.model;

import java.util.List;

/** Untrusted proposed names are inspected in memory, never copied to diagnostic/event payloads. */
public final class UndeclaredToolCallFailure extends ModelCallFailure {
    private final List<String> proposedNames;
    public UndeclaredToolCallFailure(List<String> names, ModelFailureDiagnostic.FinishReason finish) {
        super("Model requested a tool outside the current definitions",
                new ModelFailureDiagnostic(ModelFailureDiagnostic.Code.UNDECLARED_TOOL, finish, null, true));
        proposedNames = List.copyOf(names);
    }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<String> getProposedNames() { return proposedNames; }
}
