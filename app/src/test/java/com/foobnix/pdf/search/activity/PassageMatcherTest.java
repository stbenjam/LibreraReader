package com.foobnix.pdf.search.activity;

import static org.junit.Assert.*;
import android.graphics.RectF;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.junit.Test;

public class PassageMatcherTest {
    private PassageMatcher.Match match(String transcript, String book) {
        String text = PassageMatcher.normalized(book);
        return PassageMatcher.align(PassageMatcher.tokens(transcript), 7, text, PassageMatcher.tokens(text).size());
    }

    @Test public void realFourWordRecognitionFailure() {
        PassageMatcher.Match result = match("the Beast simple hunger", "There is more to this than a beast’s simple hunger and desire");
        assertNotNull(result);
        assertEquals(7, result.page);
        assertTrue(result.score >= .75);
    }

    @Test public void libraryAlignmentHandlesMissingExtraAndSwappedWords() {
        String passage = "at dawn the traveler crossed the narrow bridge and entered the silent valley";
        assertTrue(match("at dawn traveler crossed over the narrow bridge and entered the silent valley", passage).score > .8);
        assertTrue(match("at dawn the traveler crossed the bridge narrow and entered the silent valley", passage).score > .8);
    }

    @Test public void twoAndThreeWordsNeverAutoJump() {
        LiveMatchPolicy policy = new LiveMatchPolicy();
        assertFalse(policy.shouldJump("simple hunger", Collections.singletonList(match("simple hunger", "a beast simple hunger"))));
        assertFalse(policy.shouldJump("beast simple hunger", Collections.singletonList(match("beast simple hunger", "a beast simple hunger"))));
        assertNotNull(match("simple hunger", "a beast simple hunger"));
    }

    @Test public void ambiguousRepeatedPhrasesNeverAutoJump() {
        LiveMatchPolicy policy = new LiveMatchPolicy();
        java.util.List<PassageMatcher.Match> alternatives = Arrays.asList(
                new PassageMatcher.Match(7, 1, "first"), new PassageMatcher.Match(50, 1, "second"));
        assertFalse(policy.shouldJump("the old wooden bridge", alternatives));
        assertFalse(policy.shouldJump("crossing the old wooden bridge", alternatives));
    }

    @Test public void revisedTranscriptChangesWinnerAndRequiresNewConfirmation() {
        LiveMatchPolicy policy = new LiveMatchPolicy();
        assertFalse(policy.shouldJump("the beast simple hunger", Collections.singletonList(new PassageMatcher.Match(7, .8, ""))));
        assertFalse(policy.shouldJump("the east simple hunger", Collections.singletonList(new PassageMatcher.Match(25, .9, ""))));
        assertFalse(policy.shouldJump("than a beast simple hunger", Collections.singletonList(new PassageMatcher.Match(7, 1, ""))));
        assertTrue(policy.shouldJump("than a beast simple hunger and desire", Collections.singletonList(new PassageMatcher.Match(7, 1, ""))));
        policy.reset();
        assertFalse(policy.shouldJump("than a beast simple hunger and desire", Collections.singletonList(new PassageMatcher.Match(7, 1, ""))));
    }

    @Test public void duplicatePartialIsNotAnIndependentConfirmation() {
        LiveMatchPolicy policy = new LiveMatchPolicy();
        java.util.List<PassageMatcher.Match> result = Collections.singletonList(new PassageMatcher.Match(7, 1, ""));
        assertFalse(policy.shouldJump("a beast simple hunger", result));
        assertFalse(policy.shouldJump("a beast simple hunger", result));
    }

    @Test public void previewPreservesBookPunctuationAndCase() {
        String source = "Then Fitz said, ‘More than a beast’s simple hunger!’ He turned away.";
        PassageMatcher.Match result = PassageMatcher.align(PassageMatcher.tokens("the beast simple hunger"),
                7, PassageMatcher.normalized(source), PassageMatcher.tokens(source).size(), source);
        assertTrue(result.preview.contains("Fitz said, ‘More than a beast’s simple hunger!’"));
        assertEquals("First page.", PassageMatcher.prefix("First page. Second page.", 2));
    }

    @Test public void boundaryMatchMapsToFollowingPage() {
        String text = "the old captain opened the wooden door and found a letter inside";
        PassageMatcher.Match result = PassageMatcher.align(PassageMatcher.tokens("wooden door and found a letter"), 3, text, 6);
        assertNotNull(result);
        assertEquals(4, result.page);
        assertEquals(1.0, result.score, .001);
    }

    /** One line of TextWords, the shape the codec hands back for a page. */
    private TextWord[][] page(String text) {
        String[] parts = text.split(" ");
        TextWord[] line = new TextWord[parts.length];
        for (int i = 0; i < parts.length; i++) line[i] = new TextWord(parts[i], new RectF());
        return new TextWord[][]{line};
    }

    private String highlighted(List<TextWord> words) {
        StringBuilder result = new StringBuilder();
        for (TextWord word : words) {
            if (result.length() > 0) result.append(' ');
            result.append(word.w);
        }
        return result.toString();
    }

    @Test public void locateMarksTypedPhraseExactly() {
        List<TextWord> hits = PassageMatcher.locate(PassageMatcher.tokens("narrow bridge"),
                page("at dawn the traveler crossed the narrow bridge and entered"), PassageMatcher.HIGHLIGHT_MIN_SCORE);
        assertEquals("narrow bridge", highlighted(hits));
    }

    @Test public void locateMarksPassageDespiteRecognitionError() {
        List<TextWord> hits = PassageMatcher.locate(PassageMatcher.tokens("the Beast simple hunger"),
                page("There is more to this than a beast's simple hunger and desire"),
                PassageMatcher.HIGHLIGHT_MIN_SCORE);
        assertEquals("a beast's simple hunger", highlighted(hits));
    }

    @Test public void locateKeepsPunctuationBearingWordsWhole() {
        List<TextWord> hits = PassageMatcher.locate(PassageMatcher.tokens("dont look back"),
                page("and she said don't look back, ever"), PassageMatcher.HIGHLIGHT_MIN_SCORE);
        assertEquals("don't look back,", highlighted(hits));
    }

    @Test public void locateReturnsNothingWhenThePageDoesNotContainTheQuery() {
        assertTrue(PassageMatcher.locate(PassageMatcher.tokens("entirely unrelated wording here"),
                page("at dawn the traveler crossed the narrow bridge"), PassageMatcher.HIGHLIGHT_MIN_SCORE).isEmpty());
        assertTrue(PassageMatcher.locate(PassageMatcher.tokens("anything"), null,
                PassageMatcher.HIGHLIGHT_MIN_SCORE).isEmpty());
    }

    @Test public void typedSearchFindsSubstringsAndCjkWithoutFuzzyScoring() {
        for (String[] pair : new String[][]{{"hungering", "hunger"}, {"東京都", "東京"},
                {"她住在東京都。", "東京都"}, {"A traveller's story", "traveller"}}) {
            PassageMatcher.Match hit = PassageMatcher.typed(PassageMatcher.normalized(pair[1]), 3,
                    PassageMatcher.normalized(pair[0]), PassageMatcher.tokens(pair[0]).size(), pair[0]);
            assertNotNull(Arrays.toString(pair), hit);
            assertEquals(3, hit.page);
            assertEquals(1, hit.score, 0);
        }
    }

    @Test public void typedOverlapBelongsOnlyToItsStartingPage() {
        assertNull(PassageMatcher.typed("bridge", 0, "cover bridge", 1, "Cover bridge"));
        assertNotNull(PassageMatcher.typed("cover bridge", 0, "cover bridge", 1, "Cover bridge"));
        assertNull(PassageMatcher.typed("bridge", 0, "bridge", 0, "bridge"));
        assertNull(PassageMatcher.typed("", 0, "bridge", 1, "bridge"));
    }

    private TextWord[][] letters(String text) {
        return new TextWord[][]{text.codePoints().mapToObj(c ->
                new TextWord(new String(Character.toChars(c)), new RectF())).toArray(TextWord[]::new)};
    }

    private String joined(List<TextWord> words) {
        StringBuilder text = new StringBuilder();
        for (TextWord word : words) text.append(word.w);
        return text.toString();
    }

    @Test public void characterSelectionLocatesWholeWordsAndCjkSubstrings() {
        assertEquals("bridge", joined(PassageMatcher.locate(PassageMatcher.tokens("bridge"),
                letters("the bridge"), .5, true)));
        assertEquals("東京", joined(PassageMatcher.locate(PassageMatcher.tokens("東京"),
                letters("東京都"), .5, true)));
        assertEquals("hunger", joined(PassageMatcher.locate(PassageMatcher.tokens("hunger"),
                letters("hungering"), .5, true)));
    }

    @Test public void characterSelectionRetainsFuzzyAlignmentAndGeometry() {
        TextWord[][] characters = letters("There is more than a beast’s simple hunger and desire");
        List<TextWord> hits = PassageMatcher.locate(PassageMatcher.tokens("the Beast simple hunger"),
                characters, .5, true);
        assertEquals("abeastsimplehunger", joined(hits));
        for (TextWord hit : hits) assertTrue(Arrays.stream(characters[0]).anyMatch(word -> word == hit));
    }

    @Test public void normalizedLigaturesStillMapToTheirCodecObjects() {
        TextWord[][] characters = letters("a ﬁne bridge");
        assertEquals("ﬁnebridge", joined(PassageMatcher.locate(PassageMatcher.tokens("fine bridge"),
                characters, .5, true)));
    }

    @Test public void partialWordHighlightingAlsoWorksInWordSelectionMode() {
        assertEquals("hungering", highlighted(PassageMatcher.locate(PassageMatcher.tokens("hunger"),
                page("hungering"), .5)));
    }

    @Test public void characterPreferenceStillAcceptsWholeWordCodecOutput() {
        assertEquals("narrow bridge", highlighted(PassageMatcher.locate(PassageMatcher.tokens("narrow bridge"),
                page("the narrow bridge at dawn"), .5, true)));
    }

    @Test public void fuzzyMatchCarriesTheActualRecognitionAlternativeForHighlighting() {
        PassageMatcher.Match hit = match("the Beast simple hunger", "a beast’s simple hunger");
        assertEquals(PassageMatcher.tokens("the Beast simple hunger"), hit.queryTokens);
        assertEquals("a beast’s simple hunger", highlighted(PassageMatcher.locate(hit.queryTokens,
                page("a beast’s simple hunger"), .5)));
    }

    @Test public void chineseFuzzySearchToleratesWrongAndMissingCharacters() {
        String passage = "爱丽丝坐在河边陪着姐姐看书，忽然一只白兔从她身边跑过。";
        assertTrue(match("爱丽丝坐在和边陪着姐姐看书", passage).score >= .85);
        assertTrue(match("爱丽丝坐在河边着姐姐看书", passage).score >= .85);
        assertEquals("东 京 都", PassageMatcher.normalized("东京都"));
    }

    @Test public void traditionalChineseFuzzySearchAndHighlighting() {
        String passage = "愛麗絲坐在河邊陪著姐姐看書";
        String speech = "愛麗絲坐在和邊陪著姐姐看書";
        PassageMatcher.Match result = match(speech, passage);
        assertTrue(result.score >= .85);
        assertEquals(passage, joined(PassageMatcher.locate(result.queryTokens, letters(passage), .5, true)));
    }

    @Test public void chineseLivePolicyRequiresDistinctConfidentTranscripts() {
        LiveMatchPolicy policy = new LiveMatchPolicy();
        String passage = "爱丽丝坐在河边陪着姐姐看书";
        assertFalse(policy.shouldJump("爱丽丝坐在和边", Collections.singletonList(match("爱丽丝坐在和边", passage))));
        assertTrue(policy.shouldJump("爱丽丝坐在和边陪着姐姐", Collections.singletonList(match("爱丽丝坐在和边陪着姐姐", passage))));
    }
    @Test public void layoutHyphensNormalizeIdenticallyForIndexAndGeometry() {
        for (String[] parts : new String[][]{{"extraordi-", "nary"}, {"україн-", "ський"}}) {
            String query = parts[0].replace("-", "") + parts[1];
            assertEquals(query, PassageMatcher.normalized(String.join("\n", parts)));
            TextWord[][] words = {page(parts[0])[0], page(parts[1])[0]};
            assertEquals(String.join(" ", parts), highlighted(PassageMatcher.locate(
                    PassageMatcher.tokens(query), words, .5)));
            TextWord[][] characters = {letters(parts[0])[0], letters(parts[1])[0]};
            assertEquals(query, joined(PassageMatcher.locate(PassageMatcher.tokens(query), characters, .5, true)));
        }
    }

    @Test public void ukrainianApostrophesAndRecognitionErrorsKeepTheirGeometry() {
        String passage = "Мандрівник памʼятає старий дерев’яний міст біля річки";
        assertEquals(PassageMatcher.tokens("пам'ятає дерев'яний"), PassageMatcher.tokens("памʼятає дерев’яний"));
        String speech = "мандрівник пам'ятає старий міст біля річки";
        assertTrue(match(speech, passage).score >= .85);
        assertEquals(passage, highlighted(PassageMatcher.locate(PassageMatcher.tokens(speech), page(passage), .5)));
        assertEquals("памятає", joined(PassageMatcher.locate(PassageMatcher.tokens("пам'ятає"), letters("памʼятає"), .5, true)));
    }

    @Test public void orderedCandidatesOutrankPagesWithTheSameWordsScrambled() {
        List<String> query = PassageMatcher.tokens("alpha beta gamma delta epsilon zeta");
        int reversed = PassageMatcher.retrievalScore(query, "zeta epsilon delta gamma beta alpha");
        int imperfect = PassageMatcher.retrievalScore(query, "alpha beta mistaken delta epsilon zeta");
        int exact = PassageMatcher.retrievalScore(query, String.join(" ", query));
        assertTrue(imperfect > reversed);
        assertTrue(exact > imperfect);
    }

}
