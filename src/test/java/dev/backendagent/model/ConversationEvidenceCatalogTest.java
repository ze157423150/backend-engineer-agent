package dev.backendagent.model;

import dev.backendagent.runtime.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationEvidenceCatalogTest {
    @Test void quotesAreContinuousOriginalTextAndNeverContainExcerptMarkers() {
        String text="user rule\n"+"x".repeat(500)+"\n[消息节选；完整原文可用read_turn提取]\nnew rule\n";
        var request=new ConversationSummaryRequest("audit",null,0,1,4000,
                List.of(new ConversationMessage("turn-1-user",1,"user",text)));
        var evidence=ConversationEvidenceCatalog.build(request);
        assertFalse(evidence.isEmpty());
        for(var item:evidence){
            assertTrue(text.contains(item.getEvidenceQuote()));
            assertFalse(item.getEvidenceQuote().contains("消息节选"));
            assertFalse(item.getEvidenceQuote().contains("OMITTED"));
            assertTrue(item.getEvidenceQuote().length()<=320);
        }
    }

    @Test void priorCheckpointReferencesReceiveFreshLocalIdsWithoutRequiringOldRawMessages() {
        var prior=new ConversationSummary(1,1,List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,
                "old valid constraint","turn-1-user","original constraint",SummarySection.CONSTRAINTS)));
        var request=new ConversationSummaryRequest("audit",prior,1,2,4000,
                List.of(new ConversationMessage("turn-2-user",2,"user","new correction")));
        var evidence=ConversationEvidenceCatalog.build(request);
        assertEquals("E1",evidence.getFirst().getEvidenceId());
        assertEquals("turn-1-user",evidence.getFirst().getSourceCallId());
        assertEquals("original constraint",evidence.getFirst().getEvidenceQuote());
        assertEquals(2,evidence.size());
    }
}
