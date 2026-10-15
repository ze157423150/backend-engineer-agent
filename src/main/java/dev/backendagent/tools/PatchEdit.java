package dev.backendagent.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One replacement located against a shared immutable file baseline. */
public final class PatchEdit {
    private final String id, oldText, newText;
    @JsonCreator
    public PatchEdit(@JsonProperty("id") String id, @JsonProperty("old_text") String oldText,
                     @JsonProperty("new_text") String newText) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || oldText == null || oldText.isEmpty()
                || newText == null || oldText.length() > 24000 || newText.length() > 24000)
            throw new IllegalArgumentException("Invalid patch edit");
        this.id=id; this.oldText=oldText; this.newText=newText;
    }
    public String getId(){return id;}
    @JsonProperty("old_text") public String getOldText(){return oldText;}
    @JsonProperty("new_text") public String getNewText(){return newText;}
}
