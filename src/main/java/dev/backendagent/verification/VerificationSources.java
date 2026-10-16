package dev.backendagent.verification;

import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import java.util.*;

/** Reads the projection, never the full session archive. The ledger itself is not a visibility source. */
public final class VerificationSources {
    private VerificationSources() { }

    public static List<VerificationSource> visible(ModelRequest request) {
        var sources = new LinkedHashMap<String,VerificationSource>();
        for (var turn : request.turns()) {
            add(sources,"turn-"+turn.getTurnId()+"-user","USER",turn.getTurnId(),turn.getUserMessage(),null,"CURRENT");
            if(turn.getAnswer()!=null) add(sources,"turn-"+turn.getTurnId()+"-answer","ASSISTANT",turn.getTurnId(),turn.getAnswer(),null,"REFERENCE");
        }
        if(request.turns().isEmpty()) add(sources,"turn-1-user","USER",1,request.objective(),null,"CURRENT");
        if(request.conversationSummary()!=null) for(var n:request.conversationSummary().getNotes()) {
            if(n.getSourceCallId().endsWith("-user") || n.getSection()==SummarySection.CONSTRAINTS)
                appendSummary(sources,n.getSourceCallId(),"DIALOGUE_SUMMARY",n.getStatement());
            else add(sources,n.getSourceCallId()+"-dialogue-trace","TRACE_ONLY",0,n.getStatement(),null,"REFERENCE");
            summaryTrace(sources,n);
        }
        if(request.contextSummary()!=null) for(var n:request.contextSummary().getNotes())
            if(!request.staleSummarySourceIds().contains(n.getSourceCallId())) {
                appendSummary(sources,n.getSourceCallId(),"TOOL_SUMMARY",n.getStatement());
                summaryTrace(sources,n);
            }
        for(var m:request.workingMemory()) {
            appendSummary(sources,m.getSourceCallId(),"MEMORY",m.getStatement());
            add(sources,m.getSourceCallId()+"-memory-quote","TRACE_ONLY",0,m.getEvidenceQuote(),null,"REFERENCE");
        }
        for(var e:request.history()) {
            add(sources,e.call().id()+"-trace","TRACE_ONLY",0,
                    e.call().arguments()+"\n"+java.util.Objects.toString(e.assistantContent(),"")+"\n"+e.result().content(),null,"REFERENCE");
            if(!e.result().successful()) continue;
            // A compressed/omitted body cannot be silently promoted back to complete source material.
            if(e.call().name().equals("read_file") && e.result().fileFingerprint()!=null
                    && isContractDocument(e.result().fileFingerprint().getPath())
                    && e.result().readMetadata()!=null && e.result().readMetadata().isBodyComplete())
                add(sources,e.call().id(),"FILE",0,e.result().content(),e.result().fileFingerprint(),"CURRENT");
            if(e.call().name().equals("read_turn"))
                addRetrievedRequirement(sources,e.result().content());
        }
        for(var e:request.historicalEvidence())
            add(sources,e.getSourceCallId(),"HISTORY",0,e.getContent(),e.getSourceFile(),"REFERENCE");
        return List.copyOf(sources.values());
    }
    private static void summaryTrace(Map<String,VerificationSource> out,SummaryNote note) {
        // The stored quotation links this visible summary statement to the original rule. It is
        // used for eviction only, not sent as extra summary text or promoted to an active source.
        add(out,note.getSourceCallId()+"-quote-"+dev.backendagent.history.ArchivedBody.hash(note.getEvidenceQuote()),
                "TRACE_ONLY",0,note.getEvidenceQuote(),null,"REFERENCE");
    }
    private static void appendSummary(Map<String,VerificationSource> out,String id,String kind,String content) {
        var old=out.get(id);
        if(old!=null && !old.kind().equals(kind)) return;
        int turn=0;var match=java.util.regex.Pattern.compile("turn-([0-9]+)-(user|answer)").matcher(id);
        if(match.matches())turn=Integer.parseInt(match.group(1));
        add(out,id,kind,turn,old==null?content:old.content()+"\n"+content,null,"REFERENCE");
    }
    private static void add(Map<String,VerificationSource> out,String id,String kind,int turn,String text,FileFingerprint file,String currency) {
        if(text!=null&&!text.isBlank())out.put(id,new VerificationSource(id,kind,turn,text,file,currency));
    }

    private static void addRetrievedRequirement(Map<String,VerificationSource> out,String content) {
        try {
            var value=new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            String id=value.path("messageId").asText();int turn=value.path("turnId").asInt();
            if(!value.path("role").asText().equals("user") || !id.equals("turn-"+turn+"-user") || turn<1)return;
            String text=value.path("content").asText().replaceAll("(?m)^\\d+: ","");
            if(!out.containsKey(id))add(out,id,"USER",turn,text,null,"CURRENT");
        } catch(java.io.IOException invalid) { /* An unparseable historical result is not a requirement source. */ }
    }

    /** Code, build configuration and patch arguments do not establish business expectations. */
    public static boolean isContractDocument(String path) {
        String value=path.toLowerCase(Locale.ROOT);
        return value.endsWith(".md") || value.endsWith(".txt") || value.endsWith(".rst") || value.endsWith(".adoc")
                || (value.contains("openapi") || value.contains("contract") || value.contains("spec"))
                    && (value.endsWith(".yaml") || value.endsWith(".yml") || value.endsWith(".json"));
    }
    public static boolean isUpdateSource(VerificationSource source) {
        return source.userRequirement() || source.kind().equals("DIALOGUE_SUMMARY")
                || source.kind().equals("FILE") && source.file()!=null && isContractDocument(source.file().getPath());
    }

    /** Only already-read requirement documents, never arbitrary implementation files. */
    public static boolean relevantFile(VerificationSource source,String objective,List<ActiveVerificationBasis> previous) {
        if(source.file()==null || !isContractDocument(source.file().getPath()))return false;
        String path=source.file().getPath();
        if(previous.stream().anyMatch(b->b.source().file()!=null&&b.source().file().getPath().equals(path)))return true;
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.contains("contract")||lower.contains("spec")||lower.startsWith("docs/")||lower.endsWith(".md"))return true;
        String name=java.nio.file.Path.of(path).getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return name.length()>2&&objective.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }

    public static List<VerificationSource> updateSources(List<VerificationSource> visible,int turn,
            String objective,List<ActiveVerificationBasis> previous) {
        var material=new ArrayList<>(visible);
        String scope=objective+"\n"+visible.stream().filter(VerificationSource::userRequirement)
                .map(VerificationSource::content).collect(java.util.stream.Collectors.joining("\n"));
        material.removeIf(s->!isUpdateSource(s) || s.kind().equals("FILE")
                && (!s.currency().equals("CURRENT") || !relevantFile(s,scope,previous)));
        material.sort(Comparator.comparingInt((VerificationSource s)->s.userRequirement()?(s.turnId()==turn?0:1):
                s.kind().equals("FILE")?2:3));
        var selected=new ArrayList<VerificationSource>();int bytes=0;var files=new HashSet<String>();
        // Keep whole source units. Nothing is falsely described as complete after head truncation.
        for(var s:material) {
            if(s.kind().equals("FILE")&&!files.add(s.file().getPath()+"\n"+s.file().getSha256()+"\n"+s.content()))continue;
            int cost=VerificationUpdateRequest.sourceData(s).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes+cost<=32_000){selected.add(s);bytes+=cost;}
        }
        return List.copyOf(selected);
    }
    public static boolean externallyVisible(ActiveVerificationBasis basis,List<VerificationSource> sources) {
        return sources.stream().anyMatch(s->s.content().contains(basis.quote()));
    }
    public static List<ActiveVerificationBasis> project(VerificationLedger ledger,List<VerificationSource> visible,
                                                       Map<String,String> hashes,Set<String> staleIds) {
        if(ledger==null)return List.of();
        var result=new ArrayList<ActiveVerificationBasis>();
        for(var b:ledger.bases()) {
            if(!isUpdateSource(b.source()))continue;
            var entry=b;
            if(b.source().file()!=null) {
                var file=b.source().file();
                String currency=staleIds.contains(b.source().id())?"STALE":hashes==null?"UNKNOWN":
                        file.getSha256().equals(hashes.get(file.getPath()))?"CURRENT":"STALE";
                entry=b.withCurrency(currency);
            }
            if((entry.status().equals("SUPERSEDED")||entry.status().equals("STALE"))&&!externallyVisible(entry,visible))continue;
            result.add(entry);
        }
        return List.copyOf(result);
    }
}
