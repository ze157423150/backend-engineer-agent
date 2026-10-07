package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;
import dev.backendagent.runtime.AgentSession;

/** Historical body extraction; production scans the fixed session archive and verifies file hashes in Docker. */
public final class ReadObservationTool implements Tool {
    private final AgentSession session;
    private final dev.backendagent.history.HistoryArchiveReader archive;
    public ReadObservationTool(AgentSession session) { this(session, null); }
    public ReadObservationTool(AgentSession session, WorkspaceAccess workspace) {
        this(session, workspace, dev.backendagent.history.ObservationArchive.inMemory(session));
    }
    public ReadObservationTool(AgentSession session, WorkspaceAccess workspace,
                               dev.backendagent.history.ObservationArchive source) {
        this.session = java.util.Objects.requireNonNull(session);
        this.archive = new dev.backendagent.history.HistoryArchiveReader(source, workspace);
    }
    public String name() { return "read_observation"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "按调用 ID 取回本会话历史工具结果，最多 200 行。行号属于工具结果原文，不是源文件。旧结果不证明当前文件或测试状态。",
                Map.of("call_id", "历史工具调用 ID", "start_line", "结果起始行，从 1 开始",
                        "end_line", "结果结束行，包含该行"));
    }
    public ToolResult execute(Map<String, String> arguments) {
        try {
            int start = Integer.parseInt(arguments.get("start_line"));
            int end = Integer.parseInt(arguments.get("end_line"));
            if (start < 1 || end < start || (long) end - start + 1 > 200) {
                return new ToolResult(false, "Invalid observation range: maximum 200 lines");
            }
            var source = archive.read(arguments.get("call_id"));
            if (source == null) { return new ToolResult(false, "Unknown call_id in this session archive"); }
            var exchange = source.getExchange();
            var validity = session.staleEvidenceIds().contains(exchange.call().id())
                    ? dev.backendagent.runtime.ObservationEvidence.Validity.STALE : source.getValidity();
            String[] lines = exchange.result().content().split("\\R", -1);
            if (start > lines.length) { return new ToolResult(false, "start_line exceeds observation lines"); }
            var output = new StringBuilder("HISTORICAL_OBSERVATION call_id=" + exchange.call().id()
                    + " tool=" + exchange.call().name() + " successful=" + exchange.result().successful()
                    + " stale=" + (session.staleEvidenceIds().contains(exchange.call().id())
                        || validity == dev.backendagent.runtime.ObservationEvidence.Validity.STALE)
                    + " fileEvidenceValidity=" + validity
                    + " eventSequence=" + source.getSequence()
                    + " totalLines=" + lines.length + "\n历史结果，不能证明当前工作区状态。\n");
            if (exchange.evidence() != null) {
                var evidence = exchange.evidence();
                output.append("sourcePath=").append(evidence.getFile().getPath())
                        .append(" sha256=").append(evidence.getFile().getSha256())
                        .append(" observedRevision=").append(evidence.getWorkspaceRevision()).append('\n');
            }
            if (output.length() > 23000) { return new ToolResult(false, "Observation metadata exceeds output limit"); }
            for (int i = start - 1; i < Math.min(end, lines.length); i++) {
                String line = (i + 1) + ": " + lines[i] + "\n";
                if (output.length() + line.length() > 23500) {
                    output.append("[输出达到上限；请缩小读取范围；超长单行无法完整取回]\n");
                    break;
                }
                output.append(line);
            }
            var retained = dev.backendagent.history.HistoricalEvidence.excerpt(exchange, source.getSequence(), validity, start, end);
            // Only attach a cache entry when the full bounded excerpt actually reached the caller.
            if (retained != null && !output.toString().contains(retained.getContent())) { retained = null; }
            return new ToolResult(true, output.toString(), null, retained);
        } catch (NumberFormatException exception) {
            return new ToolResult(false, "start_line and end_line must be integers");
        } catch (IllegalArgumentException invalid) {
            return new ToolResult(false, "Invalid historical observation arguments");
        } catch (java.io.IOException failure) {
            throw new dev.backendagent.persistence.PersistenceException(
                    "Cannot read observation archive; execution stopped without memory fallback", failure);
        }
    }
}
