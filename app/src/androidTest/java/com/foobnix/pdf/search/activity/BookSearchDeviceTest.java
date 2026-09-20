package com.foobnix.pdf.search.activity;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.widget.EditText;
import android.widget.GridView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.DragingDialogs;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class BookSearchDeviceTest {
    @Test public void durableSharedIndexAndPerformance() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path = InstrumentationRegistry.getArguments().getString("book");
        boolean realBook = path != null;
        File book;
        if (realBook) book = new File(path);
        else {
            book = new File(context.getCacheDir(), "voice-search-alice.epub");
            try (InputStream in = context.getAssets().open("books/alicesadventures.epub"); FileOutputStream out = new FileOutputStream(book)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
        boolean setting = AppState.get().indexBooksOnFirstOpen;
        AppState.get().indexBooksOnFirstOpen = true;
        Intent intent = new Intent(context, HorizontalViewActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.fromFile(book)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<HorizontalViewActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.dc != null && a.dc.getBookSearch().isReady(), 360000);
            AtomicReference<BookSearch> search = new AtomicReference<>();
            scenario.onActivity(a -> search.set(a.dc.getBookSearch()));
            BookSearch service = search.get();
            if ("true".equals(InstrumentationRegistry.getArguments().getString("reuse"))) assertTrue("Rebuilt after restart", service.loadedFromDisk());
            String typed = realBook ? "simple hunger" : "Alice was beginning to get very tired";
            String spoken = realBook ? "the Beast simple hunger" : "Alice was beginning to get very tired sitting by her sister on the bank";
            List<PassageMatcher.Match> exact = query(scenario, typed, false);
            List<PassageMatcher.Match> fuzzy = query(scenario, spoken, true);
            assertFalse("Typed FTS search returned no passage", exact.isEmpty());
            assertFalse("Fuzzy FTS search returned no passage", fuzzy.isEmpty());
            assertEquals("Voice and typed search disagree", exact.get(0).page, fuzzy.get(0).page);
            assertTrue("Real recognition error scored too low", fuzzy.get(0).score >= .75);
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                query(scenario, spoken, true); times.add(service.searchMillis());
            }
            Collections.sort(times);
            Log.i("BookSearchBench", "pages=" + (realBook ? "real book" : "Alice") + " built_ms=" + service.buildMillis()
                    + " load_ms=" + service.loadMillis() + " reused=" + service.loadedFromDisk() + " bytes=" + service.savedBytes()
                    + " fuzzy_p50_ms=" + times.get(5) + " fuzzy_p95_ms=" + times.get(9)
                    + " match_page=" + (fuzzy.get(0).page + 1) + " score=" + fuzzy.get(0).score);
            String longQuery = realBook ? "he had not looked at me but it turned to the fire and stared into the failing flames there" : fuzzy.get(0).preview;
            List<Long> longTimes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                List<PassageMatcher.Match> longMatches = query(scenario, longQuery, true);
                assertFalse(longMatches.isEmpty());
                assertEquals(fuzzy.get(0).page, longMatches.get(0).page);
                longTimes.add(service.searchMillis());
            }
            Collections.sort(longTimes);
            Log.i("BookSearchBench", "long_query_p50_ms=" + longTimes.get(5) + " long_query_p95_ms=" + longTimes.get(9));
            AtomicBoolean cancelled = new AtomicBoolean(true);
            AtomicBoolean delivered = new AtomicBoolean();
            scenario.onActivity(a -> a.dc.getBookSearch().search(Collections.singletonList(spoken), true, 0, a.dc.getPageCount(),
                    cancelled::get, new BookSearch.Callback() {
                        public void progress(int done, int total) { delivered.set(true); }
                        public void complete(List<PassageMatcher.Match> matches) { delivered.set(true); }
                        public void failed(Exception error) { delivered.set(true); }
                    }));
            query(scenario, typed, false); // Queue barrier: cancelled work has finished before this callback.
            assertFalse("Cancelled search updated UI", delivered.get());
            scenario.onActivity(a -> {
                DragingDialogs.dialogSearchText(a.findViewById(R.id.anchor), a.dc, typed);
                a.findViewById(R.id.onSearch).performClick();
            });
            await(scenario, a -> { GridView grid = a.findViewById(R.id.grid1); return grid != null && grid.getCount() > 0; }, 10000);
            scenario.onActivity(a -> assertEquals(typed, ((EditText) a.findViewById(R.id.edit1)).getText().toString()));
        } finally { AppState.get().indexBooksOnFirstOpen = setting; }
    }

    private List<PassageMatcher.Match> query(ActivityScenario<HorizontalViewActivity> scenario, String text, boolean fuzzy) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<PassageMatcher.Match>> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        scenario.onActivity(a -> a.dc.getBookSearch().search(Collections.singletonList(text), fuzzy, 0, a.dc.getPageCount(),
                () -> false, new BookSearch.Callback() {
                    public void progress(int page, int total) { }
                    public void complete(List<PassageMatcher.Match> matches) { result.set(matches); done.countDown(); }
                    public void failed(Exception failure) { error.set(failure); done.countDown(); }
                }));
        assertTrue("Query timed out", done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private interface Condition { boolean satisfied(HorizontalViewActivity a); }
    private void await(ActivityScenario<HorizontalViewActivity> scenario, Condition condition, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(a -> ready.set(condition.satisfied(a)));
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        fail("Book/search did not become ready");
    }
}
