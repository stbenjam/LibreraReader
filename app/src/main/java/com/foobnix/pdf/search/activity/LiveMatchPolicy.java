package com.foobnix.pdf.search.activity;

import java.util.List;

/** Conservative UI policy; retrieval and alignment are handled by FTS and Commons Text. */
public final class LiveMatchPolicy {
    private int winner = -1;
    private int confirmations;
    private String previous = "";

    public void reset() { winner = -1; confirmations = 0; previous = ""; }

    public boolean shouldJump(String transcript, List<PassageMatcher.Match> matches) {
        String normalized = PassageMatcher.normalized(transcript);
        if (normalized.equals(previous)) return false;
        previous = normalized;
        int count = PassageMatcher.tokens(normalized).size();
        if (count < 4 || matches.isEmpty() || matches.get(0).score < 0.75
                || (matches.size() > 1 && matches.get(0).score - matches.get(1).score < 0.12)) {
            winner = -1; confirmations = 0;
            return false;
        }
        int page = matches.get(0).page;
        confirmations = winner == page ? confirmations + 1 : 1;
        winner = page;
        return confirmations >= 2;
    }
}
