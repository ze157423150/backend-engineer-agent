package dev.backendagent.memory;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import dev.backendagent.model.ToolCall;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.tools.WorkspaceAccess;

/** Imports notes into a new task only after checking saved provenance and re-reading current evidence. */
public final class MemoryLoader {
    public MemoryLoadReport load(FileSessionStore store, UUID sourceId, WorkspaceAccess workspace,
                                 AgentSession destination) throws IOException {
        if (destination.status() != AgentSession.Status.CREATED) {
            throw new IllegalStateException("Memory must be loaded before the new task starts");
        }
        JsonNode saved = store.read(sourceId);
        JsonNode snapshot = saved.path("snapshot");
        String status = snapshot.path("status").asText();
        if (saved.path("snapshotOutOfDate").asBoolean() || !(status.equals("COMPLETED")
                || status.equals("FAILED") || status.equals("BUDGET_EXHAUSTED"))) {
            throw new IOException("Source session needs a complete terminal snapshot");
        }
        JsonNode notes = snapshot.path("workingMemory");
        if (!notes.isArray() || notes.size() > WorkingMemory.MAX_FACTS || !snapshot.path("history").isArray()) {
            throw new IOException("Invalid saved memory structure");
        }
        int loaded = 0;
        int skipped = 0;
        for (JsonNode note : notes) {
            MemoryFact fact;
            ToolExchange verified;
            try {
                fact = new MemoryFact(text(note, "statement"), text(note, "sourceCallId"),
                        text(note, "sourceTool"), text(note, "sourcePath"), text(note, "evidenceQuote"));
                JsonNode source = null;
                for (JsonNode exchange : snapshot.path("history")) {
                    if (exchange.path("call").path("id").asText().equals(fact.getSourceCallId())) {
                        if (source != null) { throw new IllegalArgumentException("Duplicate source ID"); }
                        source = exchange;
                    }
                }
                if (source != null && !source.path("archivedBody").isMissingNode() && !source.path("archivedBody").isNull()) {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var inline = json.treeToValue(source, ToolExchange.class);
                    dev.backendagent.history.ObservationArchive archive = visitor -> store.visitObservations(
                            sourceId, saved.path("events").size(), visitor);
                    var entry = new dev.backendagent.history.HistoryArchiveReader(archive, null).read(fact.getSourceCallId());
                    if (entry == null) { throw new IOException("Saved memory archive source is missing"); }
                    inline.archivedBody().verify(entry.getSequence(), entry.getExchange(), inline);
                    source = json.valueToTree(entry.getExchange());
                }
                if (source == null || !source.path("result").path("successful").isBoolean()
                        || !source.path("result").path("successful").asBoolean()
                        || !text(source.path("result"), "content").contains(fact.getEvidenceQuote())
                        || !text(source.path("call"), "name").equals(fact.getSourceTool())) {
                    throw new IllegalArgumentException("Saved evidence does not match note");
                }
                JsonNode arguments = source.path("call").path("arguments");
                if (!arguments.isObject()) { throw new IllegalArgumentException("Invalid source arguments"); }
                Map<String, String> parsed = new HashMap<>();
                arguments.fields().forEachRemaining(field -> {
                    if (!field.getValue().isTextual()) { throw new IllegalArgumentException("Invalid argument type"); }
                    parsed.put(field.getKey(), field.getValue().textValue());
                });
                if (!fact.getSourcePath().equals(parsed.get("path"))) {
                    throw new IllegalArgumentException("Source path mismatch");
                }
                ToolResult current;
                switch (fact.getSourceTool()) {
                    case "read_file" -> {
                        if (!parsed.keySet().equals(java.util.Set.of("path", "start_line", "end_line"))) {
                            throw new IllegalArgumentException("Invalid read arguments");
                        }
                        current = workspace.readFile(parsed.get("path"), parsed.get("start_line"), parsed.get("end_line"));
                    }
                    case "search_code" -> {
                        if (!parsed.keySet().equals(java.util.Set.of("path", "query"))) {
                            throw new IllegalArgumentException("Invalid search arguments");
                        }
                        current = workspace.searchCode(parsed.get("path"), parsed.get("query"));
                    }
                    default -> throw new IllegalArgumentException("Unsupported evidence tool");
                }
                if (!current.successful() || !current.content().contains(fact.getEvidenceQuote())) {
                    throw new IllegalArgumentException("Current evidence no longer matches");
                }
                verified = new ToolExchange(new ToolCall("memory-load-" + UUID.randomUUID(), fact.getSourceTool(), parsed),
                        current, "程序在新任务启动时重新执行读取或搜索，验证从旧任务导入的记忆证据。", 0);
            } catch (IllegalArgumentException failure) {
                skipped++;
                continue;
            }
            // Outside validation catch: storage failures must abort loading and execution.
            destination.importVerifiedMemory(fact, verified, sourceId);
            loaded++;
        }
        return new MemoryLoadReport(loaded, skipped);
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) { throw new IllegalArgumentException("Invalid memory text field"); }
        return value.textValue();
    }
}
