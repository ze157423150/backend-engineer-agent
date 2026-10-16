package dev.backendagent.model;

import java.util.List;
import dev.backendagent.runtime.ConversationSummary;

/** Indices refer to original user turns, never tool history. */
public record ConversationSummaryRequest(String objective, ConversationSummary previousSummary,
        int fromIndex, int toIndex, int maxOutputCharacters, List<ConversationMessage> messages) {
    public ConversationSummaryRequest {
        messages = List.copyOf(messages);
        if (fromIndex < 0 || toIndex <= fromIndex || messages.isEmpty() || maxOutputCharacters < 1)
            throw new IllegalArgumentException("Invalid conversation summary request");
    }
    /** Shared encoding for planning and transport; audit quotes are offered once as evidence. */
    public com.fasterxml.jackson.databind.node.ObjectNode referenceData() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var request = this;
        var input=json.createObjectNode().put("objective",request.objective()).put("maxOutputCharacters",request.maxOutputCharacters());
        var evidence=ConversationEvidenceCatalog.build(request);
        input.set("evidence",json.valueToTree(evidence));
        var metadata=input.putArray("messages");
        for(var message:request.messages()) metadata.addObject().put("id",message.id())
                .put("turnId",message.turnId()).put("role",message.role());
        var previous=input.putArray("previousSummary");
        if(request.previousSummary()!=null) for(var note:request.previousSummary().getNotes())
            evidence.stream().filter(e->e.getSourceCallId().equals(note.getSourceCallId())&&e.getEvidenceQuote().equals(note.getEvidenceQuote()))
                .findFirst().ifPresent(e->{
                    var item=previous.addObject().put("statement",note.getStatement()).put("evidenceId",e.getEvidenceId())
                            .put("section",note.getSection()==null?"FINDINGS":note.getSection().name());
                    ConversationMessage.annotateSource(item,note.getSourceCallId());
                });
        input.put("recommendedMaximumItems",Math.max(1,Math.min(32,request.maxOutputCharacters()/240)));
        return input;
    }
}
