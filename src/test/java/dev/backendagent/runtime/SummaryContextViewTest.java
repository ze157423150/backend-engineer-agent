package dev.backendagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryContextViewTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void displayOmitsBookkeepingAndEmptySectionsButCheckpointRetainsThem() throws Exception {
        var note=new SummaryNote(SummaryNote.Kind.PROGRESS,"原文结论","read-old","原始证据",SummarySection.FINDINGS);
        var summary=new ContextSummary(7,36,List.of(note));
        var view=summary.contextData();
        assertEquals(1,view.size());assertFalse(view.has("revision"));assertFalse(view.has("coveredExchanges"));
        assertEquals(1,view.path("sections").size());assertFalse(view.path("sections").has("CONSTRAINTS"));
        assertFalse(view.path("sections").has("OPEN_ISSUES"));
        var stored=json.readTree(summary.referenceJson());assertEquals(7,stored.path("revision").asInt());assertEquals(36,stored.path("coveredExchanges").asInt());
        var restored=json.readValue(summary.referenceJson(),ContextSummary.class);
        assertEquals(7,restored.getRevision());assertEquals(36,restored.getCoveredExchanges());
        assertEquals("原始证据",restored.getNotes().getFirst().getEvidenceQuote());
        assertEquals(view,restored.contextData());
    }

    @Test void similarStatementsKeepDistinctCallsAndSeparateEvidenceEvenWithinOneCall() throws Exception {
        var notes=List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"当时返回200","read-old","version A",SummarySection.FINDINGS),
                new SummaryNote(SummaryNote.Kind.PROGRESS,"后来返回400","read-new","version B",SummarySection.FINDINGS),
                new SummaryNote(SummaryNote.Kind.PROGRESS,"同次读取的另一结论","read-new","other evidence",SummarySection.FINDINGS));
        var summary=new ContextSummary(2,3,notes);var items=summary.contextData().path("sections").path("FINDINGS");
        assertEquals(3,items.size());assertEquals("read-old",items.get(0).path("sourceCallId").asText());
        assertEquals("read-new",items.get(1).path("sourceCallId").asText());assertEquals("read-new",items.get(2).path("sourceCallId").asText());
        var restored=json.readValue(summary.referenceJson(),ContextSummary.class);
        assertEquals(List.of("version A","version B","other evidence"),restored.getNotes().stream().map(SummaryNote::getEvidenceQuote).toList());
        assertEquals(notes.stream().map(SummaryNote::getStatement).toList(),restored.getNotes().stream().map(SummaryNote::getStatement).toList());
    }

    @Test void conversationDisplayPreservesTurnAndRoleWhileStorageKeepsCoverage() throws Exception {
        var notes=List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"旧要求","turn-1-user","旧要求",SummarySection.CONSTRAINTS),
                new SummaryNote(SummaryNote.Kind.PROGRESS,"当时答复","turn-2-answer","当时答复",SummarySection.FINDINGS));
        var summary=new ConversationSummary(3,2,notes);var view=summary.contextData();
        assertFalse(view.has("revision"));assertFalse(view.has("coveredTurns"));
        assertEquals("第1轮用户（turn-1-user）",view.path("sections").path("CONSTRAINTS").get(0).path("source").asText());
        assertEquals("第2轮助手（turn-2-answer）",view.path("sections").path("FINDINGS").get(0).path("source").asText());
        assertFalse(view.path("sections").has("OPEN_ISSUES"));
        var restored=json.readValue(summary.referenceJson(),ConversationSummary.class);
        assertEquals(3,restored.getRevision());assertEquals(2,restored.getCoveredTurns());assertEquals(view,restored.contextData());
    }
}
