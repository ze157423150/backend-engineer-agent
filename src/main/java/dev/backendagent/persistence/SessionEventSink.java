package dev.backendagent.persistence;

import dev.backendagent.runtime.AgentSession;

@FunctionalInterface
public interface SessionEventSink {
    void append(AgentSession session, AgentSession.Event event, Object payload);
    default void checkpoint(AgentSession session) { }
}
