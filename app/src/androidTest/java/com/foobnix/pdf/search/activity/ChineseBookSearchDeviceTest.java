package com.foobnix.pdf.search.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises native extraction, SQLite, the search dialog and real highlight geometry. */
@RunWith(AndroidJUnit4.class)
public class ChineseBookSearchDeviceTest {
    @Test public void chineseBookSupportsTypedAndImperfectSpokenSearch() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File book = new File(context.getCacheDir(), "chinese-search-regression.txt");
        String text = "中文搜索测试\n\n爱丽丝坐在河边陪着姐姐看书，忽然一只白兔从她身边跑过。\n\n"
                + "繁體中文測試\n愛麗絲坐在河邊陪著姐姐看書，忽然一隻白兔從她身邊跑過。\n";
        Files.write(book.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Intent intent = new Intent(context, HorizontalViewActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.fromFile(book)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean letters = AppState.get().selectingByLetters;
        try (ActivityScenario<HorizontalViewActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.dc != null && a.dc.getCodecDocument() != null);
            scenario.onActivity(a -> AppState.get().selectingByLetters = true);
            List<PassageMatcher.Match> typed = query(scenario, "河边", false);
            assertFalse(typed.isEmpty());
            List<PassageMatcher.Match> spoken = query(scenario, "爱丽丝坐在和边陪着姐姐看书", true);
            assertFalse(spoken.isEmpty());
            assertEquals(typed.get(0).page, spoken.get(0).page);
            assertTrue(spoken.get(0).score >= .85);
            assertFalse(query(scenario, "河邊", false).isEmpty());
            scenario.onActivity(a -> {
                DragingDialogs.dialogSearchText(a.findViewById(R.id.anchor), a.dc, "河边");
                a.findViewById(R.id.onSearch).performClick();
            });
            await(scenario, a -> {
                GridView grid = a.findViewById(R.id.grid1);
                return grid != null && grid.getCount() > 0;
            });
            scenario.onActivity(a -> {
                GridView grid = a.findViewById(R.id.grid1);
                grid.performItemClick(null, 0, grid.getAdapter().getItemId(0));
                List<org.ebookdroid.droids.mupdf.codec.TextWord> hits = PageImageState.get().getSelectedWords(typed.get(0).page);
                assertNotNull("No native highlight geometry", hits);
                assertFalse(hits.isEmpty());
                StringBuilder selected = new StringBuilder();
                for (org.ebookdroid.droids.mupdf.codec.TextWord hit : hits) selected.append(hit.w);
                assertEquals("河边", selected.toString());
                // Same callback input the Android recognizer supplies, through the actual dialog.
                DragingDialogs.dialogSearchText(a.findViewById(R.id.anchor), a.dc, "",
                        Collections.singletonList("爱丽丝坐在和边陪着姐姐看书"));
            });
            await(scenario, a -> {
                GridView grid = a.findViewById(R.id.grid1);
                EditText input = a.findViewById(R.id.edit1);
                return input != null && input.getText().toString().contains("和边") && grid != null && grid.getCount() > 0;
            });
        } finally { AppState.get().selectingByLetters = letters; }
    }

    /** Opt-in acoustic smoke test: play the fixture aloud after BookSpeechTest logs READY. */
    @Test public void optionalLiveMandarinRecognition() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveSpeech")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File book = new File(context.getCacheDir(), "mandarin-live-search.txt");
        Files.write(book.toPath(), "爱丽丝坐在河边陪着姐姐看书，忽然一只白兔从她身边跑过。".getBytes(StandardCharsets.UTF_8));
        Intent intent = new Intent(context, HorizontalViewActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.fromFile(book)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        java.util.Locale previousLocale = java.util.Locale.getDefault();
        AtomicReference<LiveSpeechSession> speech = new AtomicReference<>();
        AtomicReference<String> recognized = new AtomicReference<>("");
        CountDownLatch matched = new CountDownLatch(1);
        try (ActivityScenario<HorizontalViewActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.dc != null && a.dc.getCodecDocument() != null);
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
            scenario.onActivity(a -> {
                speech.set(new LiveSpeechSession(a, new LiveSpeechSession.Callback() {
                    public void status(String message, boolean listening) {
                        android.util.Log.i("BookSpeechTest", message);
                    }
                    public void transcript(List<String> alternatives) {
                        recognized.set(alternatives.get(0));
                        android.util.Log.i("BookSpeechTest", "TRANSCRIPT " + alternatives.get(0));
                        if (PassageMatcher.tokens(alternatives.get(0)).size() < 6) return;
                        a.dc.getBookSearch().search(alternatives, true, 0, a.dc.getBookSearch().pageCount(),
                                () -> matched.getCount() == 0, new BookSearch.Callback() {
                                    public void progress(int done, int total) { }
                                    public void failed(Exception error) { android.util.Log.e("BookSpeechTest", "Search failed", error); }
                                    public void complete(List<PassageMatcher.Match> matches) {
                                        if (!matches.isEmpty() && matches.get(0).page == 0 && matches.get(0).score >= .75) {
                                            android.util.Log.i("BookSpeechTest", "MATCH score=" + matches.get(0).score);
                                            matched.countDown();
                                            speech.get().stop();
                                        }
                                    }
                                });
                    }
                }));
                speech.get().start();
                android.util.Log.i("BookSpeechTest", "READY for Mandarin audio");
            });
            try { assertTrue("No live Mandarin match; last transcript=" + recognized.get(), matched.await(40, TimeUnit.SECONDS)); }
            finally { scenario.onActivity(a -> { if (speech.get() != null) speech.get().stop(); }); }
        } finally { java.util.Locale.setDefault(previousLocale); }
    }

    private interface Condition { boolean test(HorizontalViewActivity activity); }
    private void await(ActivityScenario<HorizontalViewActivity> scenario, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 60000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(a -> ready.set(condition.test(a)));
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        fail("Chinese reader/search did not become ready");
    }

    private List<PassageMatcher.Match> query(ActivityScenario<HorizontalViewActivity> scenario,
                                             String text, boolean fuzzy) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<PassageMatcher.Match>> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        scenario.onActivity(a -> a.dc.getBookSearch().search(Collections.singletonList(text), fuzzy,
                0, a.dc.getBookSearch().pageCount(), () -> false, new BookSearch.Callback() {
                    public void progress(int completed, int total) { }
                    public void complete(List<PassageMatcher.Match> matches) { result.set(matches); done.countDown(); }
                    public void failed(Exception failure) { error.set(failure); done.countDown(); }
                }));
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw error.get();
        return result.get();
    }
}
