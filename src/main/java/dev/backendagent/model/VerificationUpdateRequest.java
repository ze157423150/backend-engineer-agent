package dev.backendagent.model;

import dev.backendagent.verification.*;
import java.util.*;

/** Four existing context inputs for updating the turn's requirements, without a separate candidate area. */
public record VerificationUpdateRequest(int turnId, List<VerificationSource> sources,
                                        List<ActiveVerificationBasis> previous) {
    public VerificationUpdateRequest {
        sources = List.copyOf(sources); previous = List.copyOf(previous);
        if (turnId < 1 || sources.stream().anyMatch(s->!VerificationSources.isUpdateSource(s))
                || previous.stream().anyMatch(b->!VerificationSources.isUpdateSource(b.source())))
            throw new IllegalArgumentException("Verification updates accept requirements, dialogue summaries and read contract documents only");
    }
    public String digest() {
        return digest(fileVersions(Map.of()));
    }
    /** Cumulative versions prevent eviction/re-reading and new call IDs from retriggering maintenance. */
    public Map<String,String> fileVersions(Map<String,String> attempted) {
        var versions=new TreeMap<>(attempted);
        for(var source:sources)if(source.kind().equals("FILE")&&source.file()!=null)
            versions.put(source.file().getPath(),source.file().getSha256());
        return Collections.unmodifiableMap(versions);
    }
    public String digest(Map<String,String> versions) {
        var current=sources.stream().filter(s->s.userRequirement()&&s.turnId()==turnId)
                .map(VerificationSource::content).findFirst().orElse("");
        return dev.backendagent.history.ArchivedBody.hash(VerificationState.encode(List.of(turnId,current,new TreeMap<>(versions))));
    }
    /** Minimal transport view; hashes and original source objects remain local for validation/recovery. */
    public static com.fasterxml.jackson.databind.node.ObjectNode sourceData(VerificationSource source) {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var view=json.createObjectNode().put("source",source.id()).put("text",source.content());
        if(source.turnId()>0)view.put("turn",source.turnId());
        if(source.kind().equals("DIALOGUE_SUMMARY")) {
            view.put("summary",true).put("role",source.id().endsWith("-user")?"user":source.id().endsWith("-answer")?"assistant":"historical");
        }
        if(source.file()!=null)view.put("path",source.file().getPath());
        return view;
    }
    public com.fasterxml.jackson.databind.node.ObjectNode referenceData() {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var input=json.createObjectNode();
        var current=sources.stream().filter(s->s.userRequirement()&&s.turnId()==turnId).findFirst();
        input.set("currentTask",current.map(VerificationUpdateRequest::sourceData).orElseGet(json::createObjectNode));
        var existing=input.putArray("previousBasis");
        for(var b:previous) {
            existing.add(basisData(b));
        }
        var history=input.putArray("history");var contracts=input.putArray("contracts");
        for(var s:sources) {
            if(s.userRequirement()&&s.turnId()==turnId)continue;
            if(s.kind().equals("FILE"))contracts.add(sourceData(s));else history.add(sourceData(s));
        }
        // Replacement provenance is reference material, never an extra field in a basis entry.
        var carriedReplacements=new HashSet<String>();
        for(var b:previous) {
            if(b.replacement()!=null && sources.stream().noneMatch(s->s.equals(b.replacement()))
                    && carriedReplacements.add(b.replacement().id()+"\n"+b.replacementQuote()))
                history.add(sourceData(new VerificationSource(b.replacement().id(),"USER",b.replacement().turnId(),b.replacementQuote(),null,"CURRENT")));
        }
        return input;
    }
    /** Shared input/output entry shape; provenance metadata remains outside the basis entry. */
    public static com.fasterxml.jackson.databind.node.ObjectNode basisData(ActiveVerificationBasis basis) {
        var item=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("source",basis.source().id()).put("quote",basis.quote()).put("status",basis.status());
        if(basis.status().equals("UNVERIFIED")&&!basis.reason().isBlank())item.put("reason",basis.reason());
        if(basis.replacement()!=null)item.put("replacedBy",basis.replacement().id()).put("replacementQuote",basis.replacementQuote());
        return item;
    }
    public java.util.List<ActiveVerificationBasis> validate(java.util.List<ActiveVerificationBasis> bases) {
        validateCandidate(bases);
        var unique = new HashSet<String>();
        for(var b:bases)if(!unique.add(b.source().id()+"\n"+b.quote()))
            throw new IllegalArgumentException("Duplicate verification basis");
        if(VerificationLedger.displayCharacters(bases)>VerificationLedger.MAX_CHARACTERS)
            throw new IllegalArgumentException("Verification basis display exceeds budget");
        new VerificationLedger(turnId,digest(),bases);
        return List.copyOf(bases);
    }
    /** Validate provenance before deciding whether an oversized, possibly repetitive candidate needs compaction. */
    public java.util.List<ActiveVerificationBasis> validateCandidate(java.util.List<ActiveVerificationBasis> bases) {
        var allowed = new HashSet<>(sources);
        // Carried sources are permitted as provenance, but do not make obsolete content visible again.
        previous.forEach(b -> { allowed.add(b.source()); if(b.replacement()!=null) allowed.add(b.replacement()); });
        for(var b : bases) {
            if(!VerificationSources.isUpdateSource(b.source()))throw new IllegalArgumentException("Implementation is not a verification requirement");
            if (!allowed.contains(b.source()) || b.replacement()!=null && !allowed.contains(b.replacement()))
                throw new IllegalArgumentException("Invalid basis provenance");
            if(!sources.contains(b.source()) && previous.stream().noneMatch(p->p.source().equals(b.source())&&p.quote().contains(b.quote())))
                throw new IllegalArgumentException("Carried basis cannot expand into hidden source text");
            if(b.status().equals("AVAILABLE") && b.source().file()!=null && (sources.stream()
                    .noneMatch(s->s.id().equals(b.source().id())&&s.currency().equals("CURRENT"))
                    && previous.stream().anyMatch(p->p.source().id().equals(b.source().id())
                        &&java.util.Set.of("STALE","UNVERIFIED").contains(p.status()))))
                throw new IllegalArgumentException("Cannot promote an invalidated file basis");
        }
        return List.copyOf(bases);
    }
}
