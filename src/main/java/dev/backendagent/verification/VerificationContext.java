package dev.backendagent.verification;

import java.util.*;

/** Outgoing turn-scoped view, regenerated from durable registrations and current hash checks. */
public final class VerificationContext {
    public static final class BasisView {
        private final VerificationBasis basis;private final String validity;
        BasisView(VerificationBasis basis,String validity){this.basis=basis;this.validity=validity;}
        @com.fasterxml.jackson.annotation.JsonIgnore public VerificationBasis getBasis(){return basis;}
        public String getValidity(){return validity;}
        public String getId(){return basis.getId();}public int getTurnId(){return basis.getTurnId();}
        public String getSourceCallId(){return basis.getSourceCallId();}
        public dev.backendagent.model.FileFingerprint getFile(){return basis.getFile();}
        public int getStartLine(){return basis.getStartLine();}public int getEndLine(){return basis.getEndLine();}
        public String getContent(){return validity.equals("CURRENT")?basis.getContent():
                "[VERIFICATION_BASIS_BODY_OMITTED] 依据已过期或无法核验，请重新读取并登记。";}
    }
    private final int turnId, completionRejections;
    private final String completionIssue;
    private final List<BasisView> bases;
    private final TestFailureReport latestTest;
    private final List<ActiveVerificationBasis> activeBases;
    public VerificationContext(int turnId,VerificationState state,int rejections,String issue,TestFailureReport test){
        this(turnId,state,rejections,issue,test,List.of());
    }
    public VerificationContext(int turnId,VerificationState state,int rejections,String issue,TestFailureReport test,
                               List<ActiveVerificationBasis> activeBases){
        this.turnId=turnId;this.completionRejections=rejections;this.completionIssue=issue;this.latestTest=test;
        this.bases=state.bases().stream().filter(b->VerificationSources.isContractDocument(b.getFile().getPath()))
                .map(b->new BasisView(b,state.validity(b.getId()))).toList();
        this.activeBases=List.copyOf(activeBases);
    }
    @com.fasterxml.jackson.annotation.JsonIgnore public List<ActiveVerificationBasis> activeBases(){return activeBases;}
    public List<java.util.Map<String,Object>> getActiveBases(){return activeBases.stream().map(ActiveVerificationBasis::contextData).toList();}
    private VerificationContext(VerificationContext original,List<BasisView> bases) {
        this.turnId=original.turnId;this.completionRejections=original.completionRejections;
        this.completionIssue=original.completionIssue;this.latestTest=original.latestTest;
        this.activeBases=original.activeBases;this.bases=List.copyOf(bases);
    }
    public VerificationContext filterStaleRegistrations(List<VerificationSource> visible) {
        return new VerificationContext(this,bases.stream().filter(b->!b.getValidity().equals("STALE") || visible.stream()
                .anyMatch(s->s.id().equals(b.getSourceCallId())||s.content().contains(b.getBasis().getContent().strip()))).toList());
    }
    public int getTurnId(){return turnId;}public int getCompletionRejections(){return completionRejections;}
    public String getCompletionIssue(){return completionIssue;}public List<BasisView> getBases(){return bases;}
    public TestFailureReport getLatestTest(){return latestTest;}
    public int characterCount(){return VerificationState.encode(this).length();}
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty(){return bases.isEmpty()&&activeBases.isEmpty()&&completionRejections==0&&latestTest==null&&completionIssue==null;}
}
