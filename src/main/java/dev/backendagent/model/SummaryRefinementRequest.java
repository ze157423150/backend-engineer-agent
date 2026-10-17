package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.runtime.*;
import java.util.*;

/** One shortening attempt, restricted to sources already selected by the oversized candidate. */
public final class SummaryRefinementRequest {
    private final List<SummaryNote> notes;
    private final List<SummaryEvidence> evidence;
    private final int currentCharacters;
    private final int maxCharacters;
    private final int targetCharacters;

    public SummaryRefinementRequest(List<SummaryNote> notes, List<SummaryEvidence> allowedEvidence,
                                    int currentCharacters, int maxCharacters) {
        if (notes == null || notes.isEmpty() || notes.size() > SummaryContextView.MAX_NOTES
                || maxCharacters < 1 || currentCharacters <= maxCharacters) {
            throw new IllegalArgumentException("Invalid summary refinement request");
        }
        this.notes = List.copyOf(notes);
        this.currentCharacters = currentCharacters;
        this.maxCharacters = maxCharacters;
        this.targetCharacters = Math.max(1, maxCharacters * 9 / 10);
        var selected = new ArrayList<SummaryEvidence>();
        for (var note : notes) {
            if (note.getKind() == SummaryNote.Kind.NEXT_STEP || allowedEvidence.stream().noneMatch(e ->
                    e.getSourceCallId().equals(note.getSourceCallId())
                            && e.getEvidenceQuote().contains(note.getEvidenceQuote()))) {
                throw new IllegalArgumentException("Summary refinement source is not in original input");
            }
            if (selected.stream().noneMatch(e -> matches(e, note))) {
                selected.add(new SummaryEvidence("E" + (selected.size() + 1), note.getSourceCallId(), note.getEvidenceQuote()));
            }
        }
        this.evidence = List.copyOf(selected);
    }

    private static boolean matches(SummaryEvidence evidence, SummaryNote note) {
        return evidence.getSourceCallId().equals(note.getSourceCallId())
                && evidence.getEvidenceQuote().equals(note.getEvidenceQuote());
    }

    public void validateSources(List<SummaryNote> refined) {
        for (var note : refined) {
            if (evidence.stream().noneMatch(e -> matches(e, note))) {
                throw new IllegalArgumentException("Summary refinement changed source");
            }
        }
    }

    public ObjectNode referenceData() {
        var json = new ObjectMapper();
        var data = json.createObjectNode().put("currentCharacters", currentCharacters)
                .put("maxCharacters", maxCharacters).put("excessCharacters", currentCharacters - maxCharacters)
                .put("targetCharacters", targetCharacters);
        // Quotes stay local: refinement may only rewrite facts already in candidate.
        data.put("fixedMetadataReserve", 160);
        data.put("recommendedStatementCharacters", targetCharacters * 45 / 100);
        var costs = data.putArray("sourceCosts");
        for (var source : evidence) {
            var item = json.createObjectNode().put("statement", "").put("sourceCallId", source.getSourceCallId());
            if (source.getSourceCallId().matches("turn-[1-9][0-9]*-(user|answer)"))
                ConversationMessage.annotateSource(item, source.getSourceCallId());
            int itemCost = item.toString().length() + 1;
            costs.addObject().put("evidenceId", source.getEvidenceId()).put("itemMetadataCharacters", itemCost);
        }
        var candidate = data.putObject("candidate");
        for (var section : SummarySection.values()) {
            var items = candidate.putArray(section.name());
            for (var note : notes) {
                var selected = note.getSection() == null ? note.getKind() == SummaryNote.Kind.OPEN_ISSUE
                        ? SummarySection.OPEN_ISSUES : SummarySection.FINDINGS : note.getSection();
                if (selected == section) items.addObject().put("statement", note.getStatement())
                        .put("evidenceId", evidence.stream().filter(e -> matches(e, note)).findFirst().orElseThrow().getEvidenceId());
            }
        }
        return data;
    }

    public List<SummaryEvidence> getEvidence() { return evidence; }
    public int getCurrentCharacters() { return currentCharacters; }
    public int getMaxCharacters() { return maxCharacters; }
    public int getTargetCharacters() { return targetCharacters; }
}
