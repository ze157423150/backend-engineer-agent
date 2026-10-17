package dev.backendagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Model-facing facts only; revision, coverage and quotes remain in durable summaries. */
public final class SummaryContextView {
    public static final int MAX_NOTES = 32;
    public static final int MAX_STORED_CHARACTERS = 24_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private SummaryContextView() { }

    public static ObjectNode data(List<SummaryNote> notes) {
        var view = JSON.createObjectNode();
        var sections = view.putObject("sections");
        for (var section : SummarySection.values()) {
            var items = JSON.createArrayNode();
            for (var note : notes) {
                var selected = note.getSection() == null
                        ? note.getKind() == SummaryNote.Kind.OPEN_ISSUE ? SummarySection.OPEN_ISSUES : SummarySection.FINDINGS
                        : note.getSection();
                if (selected == section) items.addObject().put("statement", note.getStatement())
                        .put("sourceCallId", note.getSourceCallId());
            }
            if (!items.isEmpty()) sections.set(section.name(), items);
        }
        return view;
    }
}
