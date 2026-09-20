package com.foobnix.pdf.search.activity;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.CodecPage;
import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Android Room/FTS and production search, with controlled codec page contents. */
@RunWith(AndroidJUnit4.class)
public class BookSearchIndexDeviceTest {
    private Context context;
    private File book, directory;

    @Before public void setup() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(app.getCacheDir().toPath(), "search-regression-").toFile();
        context = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        book = new File(directory, "book.txt");
        Files.write(book.toPath(), "version one".getBytes(StandardCharsets.UTF_8));
    }

    private CodecDocument document(String[] pages, List<Integer> reads, int failAt) {
        return (CodecDocument) Proxy.newProxyInstance(CodecDocument.class.getClassLoader(),
                new Class<?>[]{CodecDocument.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPageCount": return pages.length;
                        case "isRecycled": return false;
                        case "getPageInner":
                            int index = (Integer) args[0];
                            reads.add(index);
                            if (index == failAt) throw new IllegalStateException("Interrupted extraction");
                            if (index < 0 || index >= pages.length) throw new AssertionError("Invalid native page " + index);
                            String text = pages[index];
                            return Proxy.newProxyInstance(CodecPage.class.getClassLoader(), new Class<?>[]{CodecPage.class},
                                    (p, m, a) -> {
                                        if (m.getName().equals("getText")) return text == null ? null : new TextWord[][]{
                                                Arrays.stream(text.split(" ")).map(w -> new TextWord(w, new android.graphics.RectF()))
                                                        .toArray(TextWord[]::new)};
                                        if (m.getName().equals("isRecycled")) return false;
                                        return null;
                                    });
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private BookSearch search(String[] pages, List<Integer> reads, int failAt) {
        return new BookSearch(context, document(pages, reads, failAt), book, "fixture-layout");
    }

    private List<PassageMatcher.Match> query(BookSearch search, List<String> queries, boolean fuzzy) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<PassageMatcher.Match>> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        search.search(queries, fuzzy, 0, search.pageCount(), () -> false, new BookSearch.Callback() {
            public void progress(int page, int total) { }
            public void complete(List<PassageMatcher.Match> matches) { result.set(matches); done.countDown(); }
            public void failed(Exception failure) { error.set(failure); done.countDown(); }
        });
        assertTrue("Search timed out", done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw error.get();
        return result.get();
    }

    @Test public void blankCoverDoesNotBlockTypedOrFuzzySearch() throws Exception {
        List<Integer> reads = new ArrayList<>();
        BookSearch search = search(new String[]{null, "there is more than a beast's simple hunger"}, reads, -1);
        try {
            assertEquals(1, query(search, Collections.singletonList("hunger"), false).get(0).page);
            assertEquals(1, query(search, Collections.singletonList("the Beast simple hunger"), true).get(0).page);
            assertEquals(Arrays.asList(0, 1), reads);
        } finally { search.close(); }
    }

    @Test public void chineseSubstringFuzzyAndAlternativeSelectionUseRealFts() throws Exception {
        String[] pages = {"天空很蓝，今天是个好天气。", "爱丽丝坐在河边陪着姐姐看书，忽然一只白兔跑过。",
                "愛麗絲坐在河邊陪著姐姐看書，忽然一隻白兔跑過。", "a beast's simple hunger"};
        BookSearch search = search(pages, new ArrayList<>(), -1);
        try {
            assertEquals(1, query(search, Collections.singletonList("河边"), false).get(0).page);
            PassageMatcher.Match simplified = query(search, Collections.singletonList("爱丽丝坐在和边陪着姐姐看书"), true).get(0);
            assertEquals(1, simplified.page);
            assertTrue(simplified.score >= .85);
            PassageMatcher.Match traditional = query(search, Collections.singletonList("愛麗絲坐在和邊陪著姐姐看書"), true).get(0);
            assertEquals(2, traditional.page);
            assertTrue(traditional.score >= .85);
            PassageMatcher.Match alternative = query(search, Arrays.asList("entirely unrelated narration here", "the Beast simple hunger"), true).get(0);
            assertEquals(3, alternative.page);
            assertEquals(PassageMatcher.tokens("the Beast simple hunger"), alternative.queryTokens);
        } finally { search.close(); }
    }

    @Test public void reopenReusesIndexAndChangedBookInvalidatesIt() throws Exception {
        BookSearch first = search(new String[]{"old needle"}, new ArrayList<>(), -1);
        assertEquals(1, query(first, Collections.singletonList("needle"), false).size());
        first.close();
        List<Integer> reads = new ArrayList<>();
        BookSearch reopened = search(new String[]{"old needle"}, reads, 0);
        try {
            assertEquals(1, query(reopened, Collections.singletonList("needle"), false).size());
            assertTrue(reopened.loadedFromDisk());
            assertTrue(reads.isEmpty());
        } finally { reopened.close(); }
        Files.write(book.toPath(), "version two".getBytes(StandardCharsets.UTF_8));
        BookSearch changed = search(new String[]{"new replacement"}, reads, -1);
        try {
            assertTrue(query(changed, Collections.singletonList("needle"), false).isEmpty());
            assertEquals(1, query(changed, Collections.singletonList("replacement"), false).size());
            assertFalse(changed.loadedFromDisk());
        } finally { changed.close(); }
    }

    @Test public void interruptedIndexResumesWithoutDuplicatingBoundaryOverlap() throws Exception {
        String[] pages = {"crossing the", "wooden bridge", "at dawn"};
        BookSearch interrupted = search(pages, new ArrayList<>(), 1);
        try {
            query(interrupted, Collections.singletonList("wooden bridge"), false);
            fail("Fixture extraction should stop at page two");
        } catch (IllegalStateException expected) {
            assertEquals("Interrupted extraction", expected.getMessage());
        } finally { interrupted.close(); }
        List<Integer> reads = new ArrayList<>();
        BookSearch resumed = search(pages, reads, -1);
        try {
            List<PassageMatcher.Match> hits = query(resumed, Collections.singletonList("the wooden bridge"), false);
            assertEquals(1, hits.size());
            assertEquals(0, hits.get(0).page);
            assertEquals(Arrays.asList(1, 2), reads);
            File[] databases = new File(directory, "book-search").listFiles((dir, name) -> name.endsWith(".db"));
            assertEquals(1, databases.length);
            BookSearchDatabase db = Room.databaseBuilder(context, BookSearchDatabase.class, databases[0].getAbsolutePath()).build();
            try { assertEquals("crossing the wooden bridge", db.access().page(1).text); }
            finally { db.close(); }
        } finally { resumed.close(); }
    }

    @Test public void chinesePhraseRetrievalFindsLatePagesAmongCommonCharacterDistractors() throws Exception {
        String[] pages = new String[150];
        Arrays.fill(pages, "爱丽丝坐在和朋友聊天的房间里，今天的天气很好。");
        pages[149] = "爱丽丝坐在河边陪着姐姐看书，忽然一只白兔跑过。";
        BookSearch search = search(pages, new ArrayList<>(), -1);
        try {
            PassageMatcher.Match hit = query(search, Collections.singletonList("爱丽丝坐在和边陪着姐姐看书"), true).get(0);
            assertEquals(149, hit.page);
            assertTrue(hit.score >= .85);
            android.util.Log.i("BookSearchBench", "Chinese 150-page fuzzy ms=" + search.searchMillis());
        } finally { search.close(); }
    }

    @Test public void orderedRetrievalPreservesLateExactAndImperfectMatches() throws Exception {
        String[] pages = new String[150];
        Arrays.fill(pages, "zeta epsilon delta gamma beta alpha");
        pages[149] = "alpha beta gamma delta epsilon zeta";
        BookSearch search = search(pages, new ArrayList<>(), -1);
        try {
            PassageMatcher.Match exact = query(search, Collections.singletonList("alpha beta gamma delta epsilon zeta"), true).get(0);
            assertEquals(149, exact.page);
            assertEquals(1.0, exact.score, 0);
            PassageMatcher.Match imperfect = query(search, Collections.singletonList("alpha beta gamma delta mistaken zeta"), true).get(0);
            assertEquals(149, imperfect.page);
            assertTrue(imperfect.score >= .8);
        } finally { search.close(); }
    }

    @Test public void ukrainianSearchHandlesApostrophesAndMissingWords() throws Exception {
        BookSearch search = search(new String[]{"Обкладинка", "Мандрівник памʼятає старий дерев’яний міст біля річки"},
                new ArrayList<>(), -1);
        try {
            assertEquals(1, query(search, Collections.singletonList("пам'ятає"), false).get(0).page);
            PassageMatcher.Match match = query(search, Collections.singletonList("мандрівник пам'ятає старий міст біля річки"), true).get(0);
            assertEquals(1, match.page);
            assertTrue(match.score >= .85);
        } finally { search.close(); }
    }

    @Test public void cancelledInFlightQueryCannotDeliverResultsAfterNewerQuery() throws Exception {
        CountDownLatch extracting = new CountDownLatch(1), resume = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        CodecDocument delegate = document(new String[]{"old phrase and new phrase"}, new ArrayList<>(), -1);
        CodecDocument blocked = (CodecDocument) Proxy.newProxyInstance(CodecDocument.class.getClassLoader(),
                new Class<?>[]{CodecDocument.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPageInner")) {
                        extracting.countDown();
                        if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("Extraction gate timed out");
                    }
                    return method.invoke(delegate, args);
                });
        BookSearch search = new BookSearch(context, blocked, book, "cancel-fixture");
        try {
            search.search(Collections.singletonList("old phrase"), false, 0, 1, cancelled::get, new BookSearch.Callback() {
                public void progress(int done, int total) { }
                public void complete(List<PassageMatcher.Match> matches) { delivered.set(true); }
                public void failed(Exception error) { delivered.set(true); }
            });
            assertTrue(extracting.await(10, TimeUnit.SECONDS));
            cancelled.set(true);
            resume.countDown();
            assertEquals(1, query(search, Collections.singletonList("new phrase"), false).size());
            assertFalse("Cancelled query delivered a stale result", delivered.get());
        } finally { resume.countDown(); search.close(); }
    }
}
