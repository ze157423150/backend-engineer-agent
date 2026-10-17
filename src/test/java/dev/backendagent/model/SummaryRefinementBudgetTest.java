package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.runtime.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryRefinementBudgetTest {
    @Test void toolSourceCostMatchesRestoredJsonAndQuotesStayLocal() {
        var note=new SummaryNote(SummaryNote.Kind.PROGRESS,"已有事实","call-\"quoted","原文中额外但不应传给精简模型的细节");
        var input=new SummaryRefinementRequest(List.of(note),List.of(new SummaryEvidence("old","call-\"quoted",note.getEvidenceQuote())),2400,1800);
        var data=input.referenceData();var item=new ObjectMapper().createObjectNode().put("statement","").put("sourceCallId",note.getSourceCallId());
        assertEquals(item.toString().length()+1,data.path("sourceCosts").get(0).path("itemMetadataCharacters").asInt());
        assertFalse(data.toString().contains(note.getEvidenceQuote()));
        assertEquals(note.getEvidenceQuote(),input.getEvidence().getFirst().getEvidenceQuote());
        input.validateSources(List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"事实",note.getSourceCallId(),note.getEvidenceQuote())));
        assertThrows(IllegalArgumentException.class,()->input.validateSources(List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"事实","other",note.getEvidenceQuote()))));
    }
    @Test void dialogueSourceCostIncludesRestoredTurnAndRole() {
        var note=new SummaryNote(SummaryNote.Kind.PROGRESS,"用户约束","turn-123-answer","当时原文");
        var input=new SummaryRefinementRequest(List.of(note),List.of(new SummaryEvidence("old",note.getSourceCallId(),note.getEvidenceQuote())),4500,4000);
        var item=new ObjectMapper().createObjectNode().put("statement","").put("sourceCallId",note.getSourceCallId());
        ConversationMessage.annotateSource(item,note.getSourceCallId());
        assertEquals(item.toString().length()+1,input.referenceData().path("sourceCosts").get(0).path("itemMetadataCharacters").asInt());
    }
}
