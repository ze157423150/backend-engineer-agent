package dev.backendagent.ptc;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/** Program outcome, distinct from success or failure of individual tool calls. */
public record PtcRunResult(JsonNode value, List<String> logs, String error) {
    public PtcRunResult {
        logs = List.copyOf(logs);
        if (error != null || (value != null && value.isNull())) value = null;
    }
    public boolean successful() { return error == null; }
}
