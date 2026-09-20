package com.foobnix.pdf.search.activity;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.apache.commons.text.similarity.SimilarityInput;

import org.ebookdroid.droids.mupdf.codec.TextWord;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapts FTS passages to Apache Commons Text's word-sequence alignment implementation. */
public final class PassageMatcher {
    /** Same floor the index search accepts, so anything shown as a result can also be highlighted. */
    public static final double HIGHLIGHT_MIN_SCORE = 0.5;
    // Han text has no word separators. Character tokens keep both FTS retrieval and edit
    // distance useful for Chinese speech, while other scripts retain word-level matching.
    private static final String LETTERS = "[\\p{L}\\p{N}&&[^\\p{IsHan}]]";
    private static final Pattern WORD = Pattern.compile("\\p{IsHan}|" + LETTERS + "+(?:['’ʼ]" + LETTERS + "+)*");
    private static final Pattern HYPHENATED = Pattern.compile("(?<=\\p{L})-\\s+(?=\\p{Ll})");
    private static final Set<String> COMMON = new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "is", "it",
            "was", "were", "be", "been", "he", "she", "i", "you", "they", "that", "this", "with"));

    public static final class Match {
        public final int page;
        public final double score;
        public final String preview;
        public final List<String> queryTokens;
        public Match(int page, double score, String preview) {
            this(page, score, preview, Collections.emptyList());
        }
        public Match(int page, double score, String preview, List<String> queryTokens) {
            this.page = page; this.score = score; this.preview = preview;
            this.queryTokens = Collections.unmodifiableList(new ArrayList<>(queryTokens));
        }
    }

    public static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = WORD.matcher(sourceText(text));
        while (matcher.find()) result.add(token(matcher.group()));
        return result;
    }

    public static String normalized(String text) { return String.join(" ", tokens(text)); }

    private static String token(String word) {
        word = word.replace('’', '\'').replace('ʼ', '\'').toLowerCase(Locale.ROOT);
        if (word.endsWith("'s")) word = word.substring(0, word.length() - 2);
        return word.replace("'", "");
    }

    public static List<String> retrievalQueries(List<String> words) {
        List<String> terms = new ArrayList<>();
        for (String word : words) if (!COMMON.contains(word) && !terms.contains(word)) terms.add(word);
        if (terms.isEmpty()) terms.addAll(words);
        terms.sort((a, b) -> Integer.compare(b.length(), a.length()));
        if (terms.size() > 6) terms = new ArrayList<>(terms.subList(0, 6));
        List<String> queries = new ArrayList<>();
        if (terms.isEmpty()) return queries;
        // Ordered phrases retrieve late exact matches before common terms fill the limit.
        queries.add("\"" + String.join(" ", words) + "\"");
        if (words.size() > 3) {
            for (int end = words.size(); end >= 3; end -= 2) {
                queries.add("\"" + String.join(" ", words.subList(end - 3, end)) + "\"");
            }
            if (words.size() % 2 == 0) queries.add("\"" + String.join(" ", words.subList(0, 3)) + "\"");
        }
        queries.add(String.join(" ", terms)); // FTS implicit AND: most specific first.
        // Pairs retain recall when ASR gets one or more words wrong.
        for (int a = 0; a < terms.size(); a++) for (int b = a + 1; b < terms.size(); b++) {
            queries.add(terms.get(a) + " " + terms.get(b));
        }
        queries.add(String.join(" OR ", terms));
        return queries;
    }

    /** Cheap order-sensitive ranking before the bounded, more expensive edit alignment. */
    static int retrievalScore(List<String> query, String passage) {
        String padded = " " + passage + " ";
        if (padded.contains(" " + String.join(" ", query) + " ")) return Integer.MAX_VALUE;
        Set<String> terms = new HashSet<>(Arrays.asList(passage.split(" ")));
        int score = 0;
        for (int i = 0; i < query.size(); i++) {
            if (terms.contains(query.get(i))) score++;
            if (i > 0 && padded.contains(" " + query.get(i - 1) + " " + query.get(i) + " ")) {
                score += query.size() + 1;
            }
        }
        return score;
    }

    public static Match align(List<String> query, int page, String text, int ownWords) {
        return align(query, page, text, ownWords, text);
    }

    public static Match align(List<String> query, int page, String text, int ownWords, String source) {
        if (query.size() < 2) return null;
        Window window = bestWindow(query, Arrays.asList(text.split(" ")));
        if (window == null) return null;
        int matchedPage = page + (window.start + window.length / 2 >= ownWords ? 1 : 0);
        return new Match(matchedPage, window.score,
                preview(source, Math.max(0, window.start - 4), window.start + window.length + 6), query);
    }

    /** Best-scoring word window of the passage against the query, or null when nothing aligns. */
    private static final class Window {
        final int start, length;
        final double score;
        Window(int start, int length, double score) {
            this.start = start; this.length = length; this.score = score;
        }
    }

    private static Window bestWindow(List<String> query, List<String> passage) {
        Map<Integer, Integer> starts = new HashMap<>();
        for (int q = 0; q < query.size(); q++) for (int p = 0; p < passage.size(); p++) {
            if (query.get(q).equals(passage.get(p))) {
                for (int shift = -2; shift <= 2; shift++) {
                    int start = p - q + shift;
                    if (start >= 0 && start < passage.size()) starts.merge(start, 1, Integer::sum);
                }
            }
        }
        List<Integer> windows = new ArrayList<>(starts.keySet());
        windows.sort((a, b) -> Integer.compare(starts.get(b), starts.get(a)));
        SimilarityInput<String> queryInput = input(query);
        double bestScore = -1;
        int bestStart = 0, bestLength = 0;
        int extra = Math.max(2, query.size() / 4);
        LevenshteinDistance distanceMetric = new LevenshteinDistance(Math.max(2, query.size() / 2));
        for (int start : windows.subList(0, Math.min(6, windows.size()))) {
            for (int length = Math.max(2, query.size() - extra); length <= query.size() + extra; length++) {
                if (start + length > passage.size()) break;
                int distance = distanceMetric.apply(queryInput, input(passage.subList(start, start + length)));
                if (distance < 0) continue;
                double score = 1.0 - (double) distance / Math.max(length, query.size());
                if (score > bestScore) {
                    bestScore = score; bestStart = start; bestLength = length;
                }
            }
        }
        return bestScore < 0 ? null : new Window(bestStart, bestLength, bestScore);
    }

    /** Literal typed matching, including partial words, with overlap owned by its starting page. */
    public static Match typed(String query, int page, String text, int ownWords, String source) {
        if (query.isEmpty() || ownWords == 0) return null;
        int at = text.indexOf(query);
        if (at < 0) return null;
        int first = 0;
        for (int i = 0; i < at; i++) if (text.charAt(i) == ' ') first++;
        if (first >= ownWords) return null;
        return new Match(page, 1, preview(source, Math.max(0, first - 4),
                first + tokens(query).size() + 6), tokens(query));
    }

    public static List<TextWord> locate(List<String> query, TextWord[][] lines, double minScore) {
        return locate(query, lines, minScore, false);
    }

    /** Reconstructs codec text before tokenization, retaining the geometry of every character. */
    public static List<TextWord> locate(List<String> query, TextWord[][] lines, double minScore,
                                        boolean selectingByLetters) {
        if (query == null || query.isEmpty() || lines == null) return Collections.emptyList();
        MappedText mapped = new MappedText(lines, selectingByLetters);
        String needle = String.join(" ", query);
        if (needle.isEmpty() || mapped.text.length() == 0) return Collections.emptyList();
        int start = mapped.text.indexOf(needle);
        int end = start + needle.length();
        if (start < 0) {
            if (query.size() < 2) return Collections.emptyList();
            Window window = bestWindow(query, mapped.tokens);
            if (window == null || window.score < minScore) return Collections.emptyList();
            start = mapped.starts.get(window.start);
            end = mapped.ends.get(window.start + window.length - 1);
        }
        List<TextWord> hits = new ArrayList<>();
        int previous = -1;
        for (int at = start; at < end; at++) {
            int owner = mapped.owners.get(at);
            if (owner >= 0 && owner != previous) {
                hits.add(mapped.words.get(owner));
                previous = owner;
            }
        }
        return hits;
    }

    private static final class MappedText {
        final List<TextWord> words = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        final List<Integer> owners = new ArrayList<>(), starts = new ArrayList<>(), ends = new ArrayList<>();
        final StringBuilder text = new StringBuilder();

        MappedText(TextWord[][] lines, boolean letters) {
            // DjVu and already-decoded pages can still contain whole words even when the
            // preference requests characters. Only join objects that actually are characters.
            if (letters) for (TextWord[] line : lines) {
                if (line == null) continue;
                for (TextWord word : line) {
                    if (word != null && word.w != null && word.w.codePointCount(0, word.w.length()) > 1) {
                        letters = false;
                        break;
                    }
                }
                if (!letters) break;
            }
            StringBuilder source = new StringBuilder();
            List<Integer> sourceOwners = new ArrayList<>();
            for (TextWord[] line : lines) {
                if (line == null) continue;
                for (TextWord word : line) {
                    if (word == null || word.w == null) continue;
                    int owner = words.size();
                    words.add(word);
                    String value = canonical(word.w);
                    source.append(value);
                    for (int i = 0; i < value.length(); i++) sourceOwners.add(owner);
                    if (!letters) { source.append(' '); sourceOwners.add(-1); }
                }
                source.append(' '); sourceOwners.add(-1);
            }
            // Remove layout hyphenation and its ownership entries together.
            Matcher hyphens = HYPHENATED.matcher(source);
            List<Integer> joins = new ArrayList<>();
            while (hyphens.find()) { joins.add(hyphens.start()); joins.add(hyphens.end()); }
            for (int i = joins.size() - 2; i >= 0; i -= 2) {
                source.delete(joins.get(i), joins.get(i + 1));
                sourceOwners.subList(joins.get(i), joins.get(i + 1)).clear();
            }
            Matcher matcher = WORD.matcher(source);
            while (matcher.find()) {
                if (text.length() > 0) { text.append(' '); owners.add(-1); }
                starts.add(text.length());
                String value = token(matcher.group());
                text.append(value);
                int remaining = value.length();
                for (int at = matcher.start(); at < matcher.end() && remaining > 0;) {
                    int codePoint = source.codePointAt(at);
                    String part = token(new String(Character.toChars(codePoint)));
                    for (int i = 0; i < part.length() && remaining > 0; i++, remaining--) {
                        owners.add(sourceOwners.get(at));
                    }
                    at += Character.charCount(codePoint);
                }
                ends.add(text.length());
                tokens.add(text.substring(starts.get(starts.size() - 1)));
            }
        }
    }

    private static String canonical(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replace("\u00ad", "");
    }

    /** Token positions share the index's Unicode normalization, while display keeps punctuation/case. */
    public static String sourceText(String text) {
        return HYPHENATED.matcher(canonical(text)).replaceAll("").trim();
    }

    public static String tail(String text, int count) {
        String source = sourceText(text);
        Matcher matcher = WORD.matcher(source);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) starts.add(matcher.start());
        return starts.size() > count ? source.substring(starts.get(starts.size() - count)) : source;
    }

    public static String prefix(String text, int count) {
        Matcher matcher = WORD.matcher(text);
        for (int word = 0; word < count; word++) if (!matcher.find()) return text;
        return count == 0 ? "" : matcher.find() ? text.substring(0, matcher.start()).trim() : text;
    }

    public static String preview(String text, int first, int last) {
        Matcher matcher = WORD.matcher(text);
        int word = 0, begin = 0, end = text.length();
        while (matcher.find()) {
            if (word == first && first > 0) begin = matcher.start();
            if (word == last) { end = matcher.start(); break; }
            word++;
        }
        return (begin > 0 ? "…" : "") + text.substring(begin, end).trim() + (end < text.length() ? "…" : "");
    }

    private static SimilarityInput<String> input(List<String> words) {
        return new SimilarityInput<String>() {
            public String at(int index) { return words.get(index); }
            public int length() { return words.size(); }
        };
    }
}
