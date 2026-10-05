package dev.backendagent.persistence;

/** Storage failures must stop execution rather than become ordinary tool feedback. */
public final class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) { super(message, cause); }
}
