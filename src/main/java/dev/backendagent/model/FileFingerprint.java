package dev.backendagent.model;

import java.nio.file.Path;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** SHA-256 of the exact full file bytes used to render a bounded read result. */
public final class FileFingerprint {
    private final String path;
    private final String sha256;

    @JsonCreator
    public FileFingerprint(@JsonProperty("path") String path, @JsonProperty("sha256") String sha256) {
        if (path == null || path.isBlank() || Path.of(path).isAbsolute()
                || !Path.of(path).normalize().toString().equals(path) || path.equals(".")
                || Path.of(path).startsWith("..") || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid file fingerprint");
        }
        this.path = path;
        this.sha256 = sha256;
    }
    public String getPath() { return path; }
    public String getSha256() { return sha256; }
    @Override public boolean equals(Object other) {
        return other instanceof FileFingerprint fingerprint && path.equals(fingerprint.path) && sha256.equals(fingerprint.sha256);
    }
    @Override public int hashCode() { return Objects.hash(path, sha256); }
}
