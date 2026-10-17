package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.verification.*;
import java.util.*;

/** One bounded compaction call; only candidate excerpts and the current user requirement are exposed. */
public record VerificationCompactionRequest(VerificationUpdateRequest original,
                                           List<ActiveVerificationBasis> candidate,
                                           List<VerificationSource> visible,int maxOutputCharacters) {
    public VerificationCompactionRequest(VerificationUpdateRequest original,List<ActiveVerificationBasis> candidate,List<VerificationSource> visible) {
        this(original,candidate,visible,VerificationLedger.COMPACT_MAX_CHARACTERS);
        if(VerificationLedger.displayCharacters(candidate)<=VerificationLedger.MAX_CHARACTERS)
            throw new IllegalArgumentException("Verification basis does not require compaction");
    }
    public VerificationCompactionRequest {
        candidate=List.copyOf(candidate);visible=List.copyOf(visible);
        original.validateCandidate(candidate);
        if(maxOutputCharacters<1||maxOutputCharacters>VerificationLedger.COMPACT_MAX_CHARACTERS
                ||VerificationLedger.displayCharacters(candidate)<=maxOutputCharacters)
            throw new IllegalArgumentException("Invalid verification compaction target");
    }
    public ObjectNode referenceData() {
        var json=new ObjectMapper();
        var data=json.createObjectNode().put("currentCharacters",VerificationLedger.displayCharacters(candidate))
                .put("hardLimitCharacters",VerificationLedger.MAX_CHARACTERS)
                .put("maxOutputCharacters",maxOutputCharacters);
        data.set("currentTask",original.sources().stream().filter(s->s.userRequirement()&&s.turnId()==original.turnId())
                .findFirst().map(VerificationUpdateRequest::sourceData).orElseGet(json::createObjectNode));
        var entries=data.putArray("candidate");candidate.forEach(b->entries.add(VerificationUpdateRequest.basisData(b)));
        var required=data.putArray("requiredHistoricalReminderIndexes");
        for(int i=0;i<candidate.size();i++)if(isRequiredReminder(candidate.get(i)))required.add(i);
        return data;
    }
    private boolean isRequiredReminder(ActiveVerificationBasis basis) {
        return Set.of("STALE","SUPERSEDED").contains(basis.status())&&VerificationSources.externallyVisible(basis,visible);
    }
    public List<ActiveVerificationBasis> validate(List<ActiveVerificationBasis> refined) {
        original.validate(refined);
        int size=VerificationLedger.displayCharacters(refined);
        if(size>maxOutputCharacters)
            throw new IllegalArgumentException("Compacted verification basis must be below 4000 characters");
        if(refined.isEmpty()&&candidate.stream().anyMatch(b->!Set.of("STALE","SUPERSEDED").contains(b.status())))
            throw new IllegalArgumentException("Compaction discarded all active requirements");
        for(var basis:refined) {
            if(!coveredExcerpt(basis))throw new IllegalArgumentException("Compaction changed evidence or promoted its status");
            if(basis.replacement()!=null&&!candidate.stream().anyMatch(b->Objects.equals(b.replacement(),basis.replacement())
                        &&b.replacementQuote().contains(basis.replacementQuote()))
                    &&original.sources().stream().noneMatch(s->s.userRequirement()&&s.turnId()==original.turnId()
                        &&s.equals(basis.replacement())&&s.content().contains(basis.replacementQuote())))
                throw new IllegalArgumentException("Compaction introduced a hidden replacement source");
        }
        for(var old:candidate)if(isRequiredReminder(old)&&refined.stream().noneMatch(b->b.source().equals(old.source())
                &&b.status().equals(old.status())&&b.quote().equals(old.quote())&&Objects.equals(b.replacement(),old.replacement())))
            throw new IllegalArgumentException("Compaction lost a still-visible historical warning");
        return List.copyOf(refined);
    }
    private boolean coveredExcerpt(ActiveVerificationBasis result) {
        var excerpts=candidate.stream().filter(b->b.source().equals(result.source())&&statusAllowed(b,result)).toList();
        if(excerpts.stream().anyMatch(b->b.quote().contains(result.quote())))return true;
        // Permit adjacent excerpts from one source to merge, including only whitespace between them.
        String text=result.source().content();var ranges=new ArrayList<int[]>();
        for(var b:excerpts)for(int at=text.indexOf(b.quote());at>=0;at=text.indexOf(b.quote(),at+1))
            ranges.add(new int[]{at,at+b.quote().length()});
        ranges.sort(Comparator.comparingInt(r->r[0]));
        var merged=new ArrayList<int[]>();
        for(var range:ranges) {
            if(merged.isEmpty())merged.add(range.clone());
            else {
                var last=merged.getLast();
                if(range[0]<=last[1]||text.substring(last[1],range[0]).isBlank())last[1]=Math.max(last[1],range[1]);
                else merged.add(range.clone());
            }
        }
        for(int at=text.indexOf(result.quote());at>=0;at=text.indexOf(result.quote(),at+1)) {
            int start=at;
            if(merged.stream().anyMatch(r->r[0]<=start&&r[1]>=start+result.quote().length()))return true;
        }
        return false;
    }
    private static boolean statusAllowed(ActiveVerificationBasis from,ActiveVerificationBasis to) {
        if(from.status().equals(to.status()))return true;
        return !Set.of("STALE","SUPERSEDED").contains(from.status())
                &&Set.of("REFERENCE","UNVERIFIED","SUPERSEDED").contains(to.status());
    }
}
