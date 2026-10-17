package dev.backendagent.model;

import dev.backendagent.runtime.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationEvidenceCatalogTest {
    @Test void excerptBoundaryDoesNotIntroduceNewlinesIntoOriginalQuotes() {
        String original = java.util.stream.IntStream.range(0,3100)
                .mapToObj(i -> String.valueOf((char)(0x4e00+i))).collect(java.util.stream.Collectors.joining());
        String excerpt = original.substring(0,1600)
                + "\n[消息节选；完整原文可用read_turn提取]\n" + original.substring(original.length()-700);
        var request = new ConversationSummaryRequest("audit", null, 0, 1, 4000,
                List.of(new ConversationMessage("turn-1-answer",1,"assistant",excerpt)));
        var notes = new java.util.ArrayList<SummaryNote>();
        for (var item : request.evidence()) {
            assertTrue(original.contains(item.getEvidenceQuote()), item.getEvidenceId());
            assertTrue(excerpt.contains(item.getEvidenceQuote()));
            notes.add(new SummaryNote(SummaryNote.Kind.PROGRESS,"历史记录",item.getSourceCallId(),item.getEvidenceQuote()));
        }
        new ConversationSummary(1,1,notes).validateAgainst(List.of(
                new ConversationTurn(1,"audit",AgentSession.Status.COMPLETED,original,0,0)));
        assertEquals(2300, request.evidence().stream().mapToInt(e -> e.getEvidenceQuote().length()).sum());
    }

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
