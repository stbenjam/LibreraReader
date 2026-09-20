package com.foobnix.pdf.search.activity;

import android.content.Context;
import android.os.Handler;
import androidx.room.Room;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.foobnix.android.utils.Dips;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.wrapper.DocumentController;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.CodecPage;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.jsoup.Jsoup;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** One background builder and one durable index shared by every search of an open book. */
public final class BookSearch {
    private static final SearchIndexCache CACHE = new SearchIndexCache(128L * 1024 * 1024, 32);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "Book search");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CodecDocument document;
    private final File book;
    private final Context context;
    private File databaseFile;
    private final int pageCount;
    private final String layout;
    private volatile boolean closed;
    private volatile int indexedPages;
    private volatile String stage = "idle";
    private volatile long buildMillis;
    private volatile long loadMillis;
    private volatile long searchMillis;
    private volatile boolean loadedFromDisk;
    private volatile long savedBytes;
    private BookSearchDatabase database;
    private BookSearchDatabase.State state;

    public interface Callback {
        void progress(int completed, int total);
        void complete(List<PassageMatcher.Match> matches);
        void failed(Exception error);
    }

    public BookSearch(DocumentController controller) {
        this(controller.getActivity().getApplicationContext(), controller.getCodecDocument(),
                controller.getCurrentBook(), layout(controller));
    }

    // A document-scoped service; all stored and returned page indices are native, zero-based.
    BookSearch(Context context, CodecDocument document, File book, String layout) {
        this.context = context.getApplicationContext();
        this.document = document;
        this.book = book;
        this.layout = "4:" + layout;
        pageCount = document.getPageCount();
    }

    private static String layout(DocumentController controller) {
        CodecDocument document = controller.getCodecDocument();
        File book = controller.getCurrentBook();
        int width = controller.getBookWidth(), height = controller.getBookHeight();
        if (document instanceof MuPdfDocument) {
            width = ((MuPdfDocument) document).getW();
            height = ((MuPdfDocument) document).getH();
        }
        return AppsConfig.MUPDF_FZ_VERSION + ":" + width + ":" + height + ":" + document.getPageCount()
                + ":" + Dips.spToPx(BookCSS.get().fontSizeSp) + ":" + BookCSS.get().toCssString(book.getPath());
    }

    public void prepare() {
        WORKER.execute(() -> {
            try { ensureIndex(); }
            catch (CancellationException ignored) { }
            catch (Exception error) {
                stage = "failed";
                Log.e("BookSearch", "Background index failed", error);
            }
        });
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        WORKER.execute(() -> {
            if (database != null) {
                database.close();
                database = null;
                CACHE.release(databaseFile);
            }
        });
    }

    public static void clearSavedIndexes(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), "book-search");
        WORKER.execute(() -> CACHE.clear(directory));
    }
    public int pageCount() { return pageCount; }
    public boolean isReady() { return "ready".equals(stage); }
    public boolean loadedFromDisk() { return loadedFromDisk; }
    public long savedBytes() { return savedBytes; }
    public long buildMillis() { return buildMillis; }
    public long loadMillis() { return loadMillis; }
    public long searchMillis() { return searchMillis; }

    public void search(List<String> queries, boolean fuzzy, int firstPage, int lastPage,
                       BooleanSupplier cancelled, Callback callback) {
        Runnable progress = new Runnable() {
            @Override public void run() {
                if (closed || cancelled.getAsBoolean()) return;
                if (!isReady()) {
                    callback.progress(indexedPages, pageCount);
                    main.postDelayed(this, 250);
                }
            }
        };
        main.post(progress);
        WORKER.execute(() -> {
            try {
                if (closed || cancelled.getAsBoolean()) return;
                ensureIndex();
                if (closed || cancelled.getAsBoolean()) return;
                long start = SystemClock.elapsedRealtime();
                List<PassageMatcher.Match> matches = fuzzy ? findFuzzy(queries, cancelled)
                        : findTyped(queries.get(0), firstPage, lastPage, cancelled);
                if (closed || cancelled.getAsBoolean()) return;
                searchMillis = SystemClock.elapsedRealtime() - start;
                Log.i("BookSearch", "query fuzzy=" + fuzzy + " ms=" + searchMillis + " results=" + matches.size());
                main.post(() -> {
                    main.removeCallbacks(progress);
                    if (!closed && !cancelled.getAsBoolean()) callback.complete(matches);
                });
            } catch (CancellationException ignored) {
                main.removeCallbacks(progress);
            } catch (Exception error) {
                stage = "failed";
                Log.e("BookSearch", "Search failed", error);
                main.post(() -> {
                    main.removeCallbacks(progress);
                    if (!closed && !cancelled.getAsBoolean()) callback.failed(error);
                });
            }
        });
    }

    private void checkOpen() {
        if (closed || document == null || document.isRecycled()) throw new CancellationException();
    }

    private void ensureIndex() throws IOException {
        checkOpen();
        if (isReady()) return;
        if (database == null) {
            stage = "loading";
            long start = SystemClock.elapsedRealtime();
            String fingerprint = fingerprint();
            File directory = new File(context.getNoBackupFilesDir(), "book-search");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create index directory");
            databaseFile = new File(directory, hash(book.getAbsolutePath()) + "-" + hash(layout) + ".db");
            database = Room.databaseBuilder(context, BookSearchDatabase.class, databaseFile.getAbsolutePath())
                    .build();
            CACHE.acquire(databaseFile);
            try {
                state = database.access().state();
                if (state == null || !fingerprint.equals(state.fingerprint) || state.totalPages != pageCount) {
                    state = new BookSearchDatabase.State();
                    state.fingerprint = fingerprint;
                    state.totalPages = pageCount;
                    database.runInTransaction(() -> {
                        database.access().clearPassages();
                        database.access().state(state);
                    });
                }
                CACHE.accessed(databaseFile);
                indexedPages = state.indexedPages;
                loadedFromDisk = indexedPages == pageCount;
                loadMillis = SystemClock.elapsedRealtime() - start;
                if (loadedFromDisk) {
                    savedBytes = databaseFile.length();
                    stage = "ready";
                    Log.i("BookSearch", "loaded pages=" + pageCount + " ms=" + loadMillis + " bytes=" + savedBytes);
                    return;
                }
            } catch (RuntimeException error) {
                try { database.close(); }
                finally {
                    database = null;
                    state = null;
                    CACHE.release(databaseFile);
                }
                throw error;
            }
        }
        stage = "indexing";
        long start = SystemClock.elapsedRealtime();
        for (int page = state.indexedPages; page < pageCount; page++) {
            checkOpen();
            CodecPage codecPage = document.getPageInner(page);
            if (codecPage == null) throw new IOException("Cannot extract page " + (page + 1));
            String text;
            try {
                if (document instanceof MuPdfDocument) {
                    String html = codecPage.getPageHTML();
                    if (html == null) throw new IOException("Cannot extract page " + (page + 1));
                    text = Jsoup.parse(html).text();
                } else {
                    StringBuilder buffer = new StringBuilder();
                    TextWord[][] lines = codecPage.getText();
                    // DjVu uses null for a valid page with no text (e.g. an image-only cover).
                    if (lines != null) for (TextWord[] line : lines) if (line != null) {
                        for (TextWord word : line) if (word != null && word.w != null) buffer.append(word.w).append(' ');
                    }
                    text = buffer.toString();
                }
            } finally { codecPage.recycle(); }
            checkOpen();
            savePage(page, text);
            indexedPages = state.indexedPages;
        }
        try (android.database.Cursor ignored = database.getOpenHelper().getWritableDatabase()
                .query("PRAGMA wal_checkpoint(TRUNCATE)")) { ignored.moveToFirst(); }
        savedBytes = databaseFile.length();
        buildMillis += SystemClock.elapsedRealtime() - start;
        stage = "ready";
        CACHE.accessed(databaseFile);
        Log.i("BookSearch", "built pages=" + pageCount + " ms=" + buildMillis + " bytes=" + savedBytes);
    }

    private void savePage(int page, String text) {
        List<String> words = PassageMatcher.tokens(text);
        BookSearchDatabase.Passage row = new BookSearchDatabase.Passage();
        row.rowid = page + 1;
        row.text = String.join(" ", words);
        row.rawText = PassageMatcher.sourceText(text);
        row.ownWords = words.size();
        BookSearchDatabase.State nextState = new BookSearchDatabase.State();
        nextState.fingerprint = state.fingerprint;
        nextState.totalPages = state.totalPages;
        nextState.indexedPages = page + 1;
        database.runInTransaction(() -> {
            if (page > 0) {
                BookSearchDatabase.Passage previous = database.access().page(page);
                if (previous != null) {
                    // Replace overlap when resuming rather than appending it twice.
                    List<String> previousWords = Arrays.asList(previous.text.split(" "));
                    previous.text = String.join(" ", previousWords.subList(0, previous.ownWords));
                    String overlap = String.join(" ", words.subList(0, Math.min(64, words.size())));
                    previous.text = (previous.text + " " + overlap).trim();
                    previous.rawText = PassageMatcher.prefix(previous.rawText, previous.ownWords)
                            + " " + PassageMatcher.prefix(row.rawText, 64);
                    database.access().passage(previous);
                }
            }
            database.access().passage(row);
            database.access().state(nextState);
        });
        state = nextState;
    }

    private List<PassageMatcher.Match> findTyped(String text, int firstPage, int lastPage,
                                                  BooleanSupplier cancelled) {
        String query = PassageMatcher.normalized(text);
        if (query.isEmpty()) return Collections.emptyList();
        List<PassageMatcher.Match> matches = new ArrayList<>();
        int after = Math.max(0, firstPage);
        int end = Math.min(pageCount, lastPage);
        // Literal substring matching preserves partial words and CJK. Read bounded batches;
        // fuzzy queries still use the inverted index and never perform this scan.
        while (after < end) {
            if (closed || cancelled.getAsBoolean()) throw new CancellationException();
            List<BookSearchDatabase.Passage> rows = database.access().typed(query, after, end, 64);
            if (rows.isEmpty()) break;
            for (BookSearchDatabase.Passage row : rows) {
                PassageMatcher.Match match = PassageMatcher.typed(query, row.rowid - 1,
                        row.text, row.ownWords, row.rawText);
                if (match != null) matches.add(match);
            }
            after = rows.get(rows.size() - 1).rowid;
        }
        return matches;
    }

    private List<PassageMatcher.Match> findFuzzy(List<String> alternatives, BooleanSupplier cancelled) {
        Map<Integer, PassageMatcher.Match> best = new HashMap<>();
        for (String alternative : alternatives.subList(0, Math.min(3, alternatives.size()))) {
            List<String> words = PassageMatcher.tokens(alternative);
            if (words.size() < 2) continue;
            words = words.subList(Math.max(0, words.size() - 20), words.size());
            Map<Integer, BookSearchDatabase.Passage> candidates = new LinkedHashMap<>();
            for (String query : PassageMatcher.retrievalQueries(words)) {
                if (cancelled.getAsBoolean() || closed) return Collections.emptyList();
                for (BookSearchDatabase.Passage row : database.access().candidates(query, 80)) {
                    candidates.putIfAbsent(row.rowid, row);
                }
                if (candidates.size() >= 120) break;
            }
            // Rank ordered evidence before lexical overlap; alignment stays bounded per update.
            Map<Integer, Integer> overlap = new HashMap<>();
            for (BookSearchDatabase.Passage row : candidates.values()) {
                overlap.put(row.rowid, PassageMatcher.retrievalScore(words, row.text));
            }
            List<BookSearchDatabase.Passage> shortlist = new ArrayList<>(candidates.values());
            shortlist.sort(Comparator.comparingInt((BookSearchDatabase.Passage row) -> -overlap.get(row.rowid))
                    .thenComparingInt(row -> row.rowid));
            for (BookSearchDatabase.Passage row : shortlist.subList(0, Math.min(8, shortlist.size()))) {
                if (cancelled.getAsBoolean() || closed) return Collections.emptyList();
                PassageMatcher.Match match = PassageMatcher.align(words, row.rowid - 1, row.text, row.ownWords, row.rawText);
                if (match != null && match.page < pageCount && match.score >= 0.5
                        && (!best.containsKey(match.page) || best.get(match.page).score < match.score)) best.put(match.page, match);
            }
        }
        List<PassageMatcher.Match> matches = new ArrayList<>(best.values());
        matches.sort(Comparator.comparingDouble((PassageMatcher.Match m) -> -m.score).thenComparingInt(m -> m.page));
        return new ArrayList<>(matches.subList(0, Math.min(5, matches.size())));
    }

    private String fingerprint() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(book)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = in.read(bytes)) != -1) {
                    checkOpen();
                    digest.update(bytes, 0, count);
                }
            }
            digest.update(layout.getBytes(StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hash(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
