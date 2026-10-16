package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.runtime.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationSummaryProvenanceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void restoredNotesGainSourceMetadataWithoutChangingStatementsOrStorage() throws Exception {
        var notes = List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS, "保持公共接口", "turn-1-user", "保持公共接口", SummarySection.CONSTRAINTS),
                new SummaryNote(SummaryNote.Kind.PROGRESS, "错误分子仅5xx", "turn-4-answer", "错误分子仅5xx", SummarySection.CONSTRAINTS));
        var original = new ConversationSummary(1, 4, notes);
        var restored = json.readValue(original.referenceJson(), ConversationSummary.class);
        var entries = restored.contextData().path("sections").path("CONSTRAINTS");
        assertEquals("第1轮用户（turn-1-user）", entries.get(0).path("source").asText());
        assertEquals("第4轮助手（turn-4-answer）", entries.get(1).path("source").asText());
        assertEquals("错误分子仅5xx", entries.get(1).path("statement").asText());
        assertEquals(2,entries.get(1).size());
        assertFalse(entries.get(1).has("sourceCallId"));
        assertFalse(entries.get(1).has("sourceTurnId"));
        assertFalse(entries.get(1).has("sourceRole"));
        assertFalse(restored.referenceJson().contains("sourceRole"));
        assertFalse(restored.contextData().toString().contains("evidenceQuote"));
        assertEquals(restored.contextData().toString().length(), restored.characterCount());
        assertTrue(restored.characterCount() > SummaryContextView.data(1,"coveredTurns",4,notes).toString().length());
        assertFalse(new ContextSummary(1,4,notes).contextData().toString().contains("sourceRole"));
    }

    @Test void nextCompressionReceivesSourceMetadataForCarriedNotesAndNewMessages() {
        var prior = new ConversationSummary(1,4,List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "错误分子仅5xx", "turn-4-answer", "错误分子仅5xx", SummarySection.CONSTRAINTS)));
        var request = new ConversationSummaryRequest("current",prior,4,5,4000,
                List.of(new ConversationMessage("turn-5-user",5,"user","改为4xx与5xx但排除429")));
        var reference = request.referenceData();
        var carried = reference.path("previousSummary").get(0);
        assertEquals("第4轮助手（turn-4-answer）",carried.path("source").asText());
        assertTrue(carried.has("evidenceId"));
        var message = reference.path("messages").get(0);
        assertEquals("第5轮用户（turn-5-user）",message.path("source").asText());
        assertEquals(1,message.size());
    }
}
