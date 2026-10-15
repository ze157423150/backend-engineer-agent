package dev.backendagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Model-facing checkpoint. Quotes remain in durable notes for provenance validation only. */
public final class SummaryContextView {
    public static final int MAX_NOTES = 32;
    public static final int MAX_STORED_CHARACTERS = 24_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private SummaryContextView() { }

    public static ObjectNode data(int revision, String coverageName, int coverage, List<SummaryNote> notes) {
        var view = JSON.createObjectNode().put("revision", revision).put(coverageName, coverage);
        var sections = view.putObject("sections");
        for (var section : SummarySection.values()) {
            var items = sections.putArray(section.name());
            for (var note : notes) {
                var selected = note.getSection() == null
                        ? note.getKind() == SummaryNote.Kind.OPEN_ISSUE ? SummarySection.OPEN_ISSUES : SummarySection.FINDINGS
                        : note.getSection();
                if (selected == section) items.addObject().put("statement", note.getStatement())
                        .put("sourceCallId", note.getSourceCallId());
            }
        }
        return view;
    }
}
