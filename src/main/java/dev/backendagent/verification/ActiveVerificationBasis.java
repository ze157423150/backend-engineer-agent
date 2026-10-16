package dev.backendagent.verification;

/** Exact source excerpt. A replacement must point to a later user requirement, never implementation output. */
public record ActiveVerificationBasis(VerificationSource source, String quote, String status,
                                      String reason, VerificationSource replacement, String replacementQuote) {
    public ActiveVerificationBasis {
        if (source == null || quote == null || quote.isBlank() || quote.length() > 700
                || !source.content().contains(quote) || reason == null || reason.length() > 180
                || !java.util.Set.of("AVAILABLE", "REFERENCE", "SUPERSEDED", "STALE", "UNVERIFIED").contains(status))
            throw new IllegalArgumentException("Invalid active verification basis");
        if (status.equals("AVAILABLE") && (!source.currency().equals("CURRENT")
                || !(source.userRequirement() || source.kind().equals("FILE"))))
            throw new IllegalArgumentException("Historical interpretation is not a current contract");
        if (status.equals("SUPERSEDED") && (replacement == null || !replacement.userRequirement()
                || replacement.turnId() <= source.turnId() || replacementQuote == null || replacementQuote.isBlank()
                || replacementQuote.length() > 700 || !replacement.content().contains(replacementQuote)))
            throw new IllegalArgumentException("Superseded basis requires a later user source");
        if (!status.equals("SUPERSEDED") && (replacement != null || replacementQuote != null))
            throw new IllegalArgumentException("Unexpected replacement source");
    }
    public ActiveVerificationBasis withCurrency(String currency) {
        if (status.equals("SUPERSEDED")) return this;
        String next = currency.equals("STALE") ? "STALE" : currency.equals("CURRENT") ? status : "UNVERIFIED";
        return new ActiveVerificationBasis(source, quote, next,
                currency.equals("STALE") ? "来源文件已变化，旧内容不能作为当前测试依据" : reason, replacement, replacementQuote);
    }
    /** Do not duplicate complete source messages and audit quotations in the task request. */
    public java.util.Map<String,Object> contextData() {
        var view = new java.util.LinkedHashMap<String,Object>();
        view.put("source", source.id()); view.put("kind", source.kind());
        view.put("status", status); view.put("content", quote);
        if (!reason.isBlank()) view.put("reason", reason);
        if (source.file() != null) view.put("path", source.file().getPath());
        if (replacement != null) view.put("replacedBy", replacement.id());
        return view;
    }
}
