package dev.backendagent.cli;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.backendagent.runtime.*;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveSessionsTest {
    final StringWriter output = new StringWriter();
    AgentOptions options(String... extra) {
        var args = new ArrayList<>(List.of("--interactive", "--workspace", ".", "--max-model-calls", "20"));
        args.addAll(List.of(extra)); return AgentOptions.parse(args.toArray(String[]::new));
    }
    static class FakeFactory implements InteractiveSessions.Factory {
        final AgentSession a = new AgentSession("A request");
        final AgentSession b = new AgentSession("B request");
        final Map<UUID, AgentSession> saved = new HashMap<>();
        final Map<UUID, List<ModelRequest>> requests = new HashMap<>();
        final Map<UUID, Integer> openedLimits = new HashMap<>();
        final Map<UUID, AtomicInteger> closed = new HashMap<>();
        int creates, lists, preparations, deletions, deletionCloses;
        public InteractiveSessions.Attachment create(String task) {
            var s = creates++ == 0 ? a : b; saved.put(s.id(), s); return attachment(s,20);
        }
        public InteractiveSessions.Attachment open(UUID id, int limit, boolean retry) throws IOException {
            if (!saved.containsKey(id)) throw new IOException("not found");
            openedLimits.put(id,limit); return attachment(saved.get(id),limit);
        }
        InteractiveSessions.Attachment attachment(AgentSession s, int limit) {
            var counter = new AtomicInteger();
            ModelClient model = request -> {
                requests.computeIfAbsent(s.id(), ignored -> new ArrayList<>()).add(request);
                if (counter.incrementAndGet() % 2 == 1)
                    return ModelResponse.callTool(new ToolCall(s.id()+"-"+s.modelCalls(),"marker",Map.of()));
                return ModelResponse.finish("answer "+s.objective());
            };
            Tool marker = new Tool() {
                public String name() { return "marker"; }
                public ToolResult execute(Map<String,String> args) { return new ToolResult(true,"evidence "+s.objective()); }
            };
            return new InteractiveSessions.Attachment(s, limit, cap -> new AgentRuntime(model,List.of(marker),cap),
                    session -> {}, null, () -> closed.computeIfAbsent(s.id(), ignored -> new AtomicInteger()).incrementAndGet());
        }
        public InteractiveSessions.Deletion prepareDelete(UUID id) throws IOException {
            if (!saved.containsKey(id)) throw new IOException("not found");
            preparations++;
            return new InteractiveSessions.Deletion() {
                public String preview() { return "任务："+saved.get(id).objective()+"；未写回改动：1"; }
                public String delete() { deletions++;saved.remove(id);return "已删除会话："+id; }
                public void close() { deletionCloses++; }
            };
        }
        public String list() { lists++; return saved.keySet().toString(); }
    }
    void run(AgentOptions options, FakeFactory factory, String commands) throws Exception {
        new InteractiveSessions(options,new BufferedReader(new StringReader(commands)),new PrintWriter(output,true),factory).run();
    }
    @Test void switchesBackWithoutMixingTurnsToolsOrLimits() throws Exception {
        var f = new FakeFactory();
        run(options("--task","A request"),f,"/budget 30\n/sessions\n/new\nB request\n/open "+f.a.id()+"\nA followup\n/exit\n");
        assertEquals(2,f.a.turns().size()); assertEquals(1,f.b.turns().size());
        assertEquals("A followup",f.a.turns().getLast().getUserMessage());
        assertEquals(2,f.a.history().size()); assertEquals(1,f.b.history().size());
        assertTrue(f.a.history().stream().allMatch(x->x.result().content().contains("A request")));
        assertTrue(f.b.history().stream().allMatch(x->x.result().content().contains("B request")));
        assertTrue(f.requests.get(f.a.id()).stream().flatMap(r->r.history().stream())
                .noneMatch(x->x.result().content().contains("B request")));
        assertEquals(30,f.openedLimits.get(f.a.id()));
        assertEquals(2,f.closed.get(f.a.id()).get()); assertEquals(1,f.closed.get(f.b.id()).get());
    }
    @Test void failedOpenKeepsCurrentSessionUsable() throws Exception {
        var f = new FakeFactory();
        run(options("--task","A request"),f,"/open "+UUID.randomUUID()+"\nA followup\n/exit\n");
        assertEquals(2,f.a.turns().size()); assertEquals(1,f.creates);
        assertEquals(1,f.closed.get(f.a.id()).get());
        assertTrue(output.toString().contains("仍在 "+f.a.id()));
    }
    @Test void lobbyCanListAndExitWithoutCreatingOrCallingModel() throws Exception {
        var f = new FakeFactory();run(options(),f,"/sessions\n/new\n/exit\n");
        assertEquals(1,f.lists); assertEquals(0,f.creates); assertTrue(f.requests.isEmpty());
    }
    @Test void opensExistingFromLobbyWithoutExecutingIt() throws Exception {
        var f = new FakeFactory();
        try(var ignored=f.create("A request")) { new AgentRuntime(r->ModelResponse.finish("old"),List.of(),20).run(f.a); }
        run(options(),f,"/open\t"+f.a.id()+"\n/status\n/exit\n");
        assertTrue(f.requests.isEmpty()); assertEquals(1,f.a.modelCalls());
    }
    @Test void selectingSameSessionAndInvalidUuidDoNotReloadOrAddTurns() throws Exception {
        var f = new FakeFactory();
        run(options("--task","A request"),f,"/open bad\n/open "+f.a.id()+"\n/exit\n");
        assertEquals(1,f.a.turns().size()); assertEquals(2,f.a.modelCalls()); assertTrue(f.openedLimits.isEmpty());
    }
    @Test void absolutePathStillCreatesNormalTaskFromLobby() throws Exception {
        var f = new FakeFactory();run(options(),f,"/home/user/test.java 请修改\n/exit\n");
        assertEquals(1,f.creates); assertEquals(2,f.a.modelCalls());
    }
    @Test void deletingCurrentSessionIsRejectedWithoutConfirmationOrModelCall() throws Exception {
        var f=new FakeFactory();
        run(options("--task","A request"),f,"/delete "+f.a.id()+"\n/status\n/exit\n");
        assertEquals(0,f.preparations);assertEquals(2,f.a.modelCalls());
        assertEquals(1,f.a.turns().size());assertTrue(output.toString().contains("不能删除当前会话"));
    }
    @Test void cancellationIsNotAddedAsUserMessageAndCurrentSessionContinues() throws Exception {
        var f=new FakeFactory();f.saved.put(f.b.id(),f.b);
        run(options("--task","A request"),f,"/delete "+f.b.id()+"\nno\nA followup\n/exit\n");
        assertEquals(0,f.deletions);assertEquals(1,f.deletionCloses);
        assertEquals(2,f.a.turns().size());assertEquals("A followup",f.a.turns().getLast().getUserMessage());
        assertTrue(f.saved.containsKey(f.b.id()));
    }
    @Test void confirmsExactUuidAndDeletesFromLobbyWithoutModelCalls() throws Exception {
        var f=new FakeFactory();f.saved.put(f.b.id(),f.b);
        run(options(),f,"/delete "+f.b.id()+"\nDELETE "+f.b.id()+"\n/sessions\n/exit\n");
        assertEquals(1,f.deletions);assertFalse(f.saved.containsKey(f.b.id()));
        assertEquals(0,f.creates);assertTrue(f.requests.isEmpty());assertEquals(1,f.deletionCloses);
    }
    @Test void wrongConfirmationAndEofBothCancel() throws Exception {
        for(String confirmation:List.of("DELETE "+UUID.randomUUID()+"\n/exit\n", "")) {
            var f=new FakeFactory();f.saved.put(f.b.id(),f.b);
            run(options(),f,"/delete "+f.b.id()+"\n"+confirmation);
            assertEquals(0,f.deletions);assertEquals(1,f.deletionCloses);
        }
    }

}
