package dev.backendagent.verification;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded extraction of observed diagnostics; never decides whether an assertion or implementation is correct. */
public final class TestFailureReport {
    public static final class Failure {
        private final String method, diagnostic, expected, actual, exceptionType;
        Failure(String method,String diagnostic){
            this.method=method;this.diagnostic=diagnostic;
            var values=Pattern.compile("expected: <(.*?)> but was: <(.*?)>").matcher(diagnostic);
            boolean matched=values.find();this.expected=matched?values.group(1):null;this.actual=matched?values.group(2):null;
            var type=Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error))\\b").matcher(diagnostic);
            this.exceptionType=type.find()?type.group(1):null;
        }
        public String getMethod(){return method;}public String getDiagnostic(){return diagnostic;}
        public String getExpected(){return expected;}public String getActual(){return actual;}
        public String getExceptionType(){return exceptionType;}
    }
    private final String callId, kind, testExecution;
    private final long workspaceRevision;
    private final Long testsExecuted;
    private final boolean successful;
    private final List<Failure> failures;
    private TestFailureReport(String id,long revision,String kind,String execution,Long count,boolean successful,List<Failure> failures){
        this.callId=id;this.workspaceRevision=revision;this.kind=kind;this.testExecution=execution;
        this.testsExecuted=count;this.successful=successful;this.failures=List.copyOf(failures);
    }
    public static TestFailureReport from(String id,long revision,boolean success,String text){
        var execution=Pattern.compile("(?m)^testExecution=(EXECUTED|NO_TESTS|UNKNOWN)$").matcher(text);
        String status=execution.find()?execution.group(1):"UNKNOWN";
        var counts=Pattern.compile("(?m)^testsExecuted=(\\d+)$").matcher(text);
        Long count=counts.find()?Long.valueOf(counts.group(1)):null;
        var failures=new ArrayList<Failure>();
        var matcher=Pattern.compile("(?m)^\\[ERROR]\\s+([A-Za-z0-9_.$]+\\.[A-Za-z0-9_$]+):\\d+\\s+([^\\r\\n]+)").matcher(text);
        while(matcher.find()&&failures.size()<6){
            String method=matcher.group(1),diagnostic=matcher.group(2);
            if(method.length()>256)method=method.substring(0,256);
            if(diagnostic.length()>400)diagnostic=diagnostic.substring(0,400);
            failures.add(new Failure(method,diagnostic));
        }
        String kind=success?"PASSED":text.contains("status=TIMED_OUT")?"TIMED_OUT":text.contains("status=INTERRUPTED")?"INTERRUPTED":
                text.contains("status=ERROR")?"ENVIRONMENT_OR_STARTUP":text.contains("Compilation failure")||text.contains("COMPILATION ERROR")?"COMPILATION":
                !failures.isEmpty()?"TEST_FAILURE":"UNCLASSIFIED";
        return new TestFailureReport(id,revision,kind,status,count,success,failures);
    }
    public String getCallId(){return callId;}public long getWorkspaceRevision(){return workspaceRevision;}
    public String getKind(){return kind;}public String getTestExecution(){return testExecution;}
    public Long getTestsExecuted(){return testsExecuted;}public boolean isSuccessful(){return successful;}
    public List<Failure> getFailures(){return failures;}
    public boolean verifiedPassed(){return successful&&testExecution.equals("EXECUTED")&&testsExecuted!=null&&testsExecuted>0;}
}
