package dev.backendagent.history;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.model.ToolResult;

/** Refers to the original result text, not to the current source file. */
public final class ArchivedBody {
    public static final int EXCERPT_CHARACTERS = 1600;
    private final long eventSequence;
    private final String sha256;
    private final int characters;
    private final int lines;

    @JsonCreator
    public ArchivedBody(@JsonProperty("eventSequence") long eventSequence,
            @JsonProperty("sha256") String sha256, @JsonProperty("characters") int characters,
            @JsonProperty("lines") int lines) {
        if (eventSequence < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || characters < 0 || lines < 1) { throw new IllegalArgumentException("Invalid archived body reference"); }
        this.eventSequence = eventSequence;
        this.sha256 = sha256;
        this.characters = characters;
        this.lines = lines;
    }

    public static ToolExchange unload(long sequence, ToolExchange original) {
        String text = original.result().content();
        if (original.archivedBody() != null) { throw new IllegalArgumentException("Archive must contain original bodies"); }
        // Small results remain inline: a reference could cost more than their original text.
        if (text.length() <= EXCERPT_CHARACTERS + 400) { return original; }
        var reference = new ArchivedBody(sequence, hash(text), text.length(), text.split("\\R", -1).length);
        String excerpt = text.substring(0, 1000) + "\n[ARCHIVED_BODY call_id=" + original.call().id()
                + "; 原文共 " + reference.lines + " 行；节选行号不能用于定位；用 read_observation 按原结果行号提取]\n"
                + text.substring(text.length() - 600);
        return new ToolExchange(original.call(), new ToolResult(original.result().successful(), excerpt,
                original.result().fileFingerprint()), original.assistantContent(), original.modelCallNumber(),
                original.evidence(), reference);
    }

    public void verify(long sequence, ToolExchange original, ToolExchange saved) {
        if (eventSequence != sequence || characters != original.result().content().length()
                || lines != original.result().content().split("\\R", -1).length
                || !sha256.equals(hash(original.result().content())) || !saved.equals(unload(sequence, original))) {
            throw new IllegalArgumentException("Archived body does not match durable observation");
        }
    }

    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public long getEventSequence() { return eventSequence; }
    public String getSha256() { return sha256; }
    public int getCharacters() { return characters; }
    public int getLines() { return lines; }
    @Override public boolean equals(Object other) {
        return other instanceof ArchivedBody body && eventSequence == body.eventSequence && characters == body.characters
                && lines == body.lines && sha256.equals(body.sha256);
    }
    @Override public int hashCode() { return java.util.Objects.hash(eventSequence, sha256, characters, lines); }
}
