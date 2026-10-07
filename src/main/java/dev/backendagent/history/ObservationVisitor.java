package dev.backendagent.history;

import java.io.IOException;
import dev.backendagent.model.ToolExchange;

@FunctionalInterface
public interface ObservationVisitor {
    void accept(long eventSequence, ToolExchange exchange) throws IOException;
}
