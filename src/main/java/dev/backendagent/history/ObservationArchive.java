package dev.backendagent.history;

import java.io.IOException;
import dev.backendagent.runtime.AgentSession;

/** Read-only observations belonging to one fixed session. */
@FunctionalInterface
public interface ObservationArchive {
    void visit(ObservationVisitor visitor) throws IOException;

    /** For unit/in-memory runtimes only; the application supplies its durable log implementation. */
    static ObservationArchive inMemory(AgentSession session) {
        return visitor -> {
            long position = 0;
            for (var exchange : session.history()) { visitor.accept(++position, exchange); }
        };
    }
}
