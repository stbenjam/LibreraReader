package com.foobnix.pdf.info;

import android.content.SharedPreferences;
import org.ebookdroid.core.codec.AbstractCodecPage;
import org.ebookdroid.core.codec.Annotation;
import com.foobnix.android.utils.LOG;
import org.mockito.MockedStatic;
import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.junit.Test;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CodecExtractionTest {
    @Test public void concurrentExtractionWaitsInsteadOfMistakingActiveMarkerForCrash() throws Exception {
        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        AtomicBoolean marked = new AtomicBoolean();
        when(preferences.edit()).thenReturn(editor);
        when(preferences.contains(anyString())).thenAnswer(call -> marked.get());
        when(editor.putBoolean(anyString(), eq(true))).thenAnswer(call -> { marked.set(true); return editor; });
        when(editor.remove(anyString())).thenAnswer(call -> { marked.set(false); return editor; });
        AbstractCodecPage page = mock(AbstractCodecPage.class,
                withSettings().useConstructor("concurrent-search-fixture").defaultAnswer(CALLS_REAL_METHODS));
        CountDownLatch extracting = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch annotating = new CountDownLatch(1);
        TextWord[][] text = new TextWord[][]{new TextWord[0]};
        List<Annotation> annotations = Collections.singletonList(mock(Annotation.class));
        doAnswer(call -> {
            extracting.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return text;
        }).when(page).getTextImpl();
        doReturn(annotations).when(page).getAnnotationsImpl();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SharedPreferences previous = Prefs.get().sp;
        Prefs.get().sp = preferences;
        try {
            Future<TextWord[][]> first = executor.submit(() -> {
                // The logger initializes Android device configuration, unavailable on the JVM.
                try (MockedStatic<LOG> ignored = mockStatic(LOG.class)) { return page.getText(); }
            });
            if (!extracting.await(5, TimeUnit.SECONDS)) {
                first.get(1, TimeUnit.SECONDS);
                fail("Extraction never started");
            }
            Future<List<Annotation>> second = executor.submit(() -> {
                annotating.countDown();
                try (MockedStatic<LOG> ignored = mockStatic(LOG.class)) { return page.getAnnotations(); }
            });
            assertTrue(annotating.await(5, TimeUnit.SECONDS));
            try {
                second.get(100, TimeUnit.MILLISECONDS);
                fail("Concurrent extraction must wait while the marker belongs to an active call");
            } catch (TimeoutException expected) { }
            release.countDown();
            assertSame(text, first.get(5, TimeUnit.SECONDS));
            assertSame(annotations, second.get(5, TimeUnit.SECONDS));
            assertFalse(marked.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            Prefs.get().sp = previous;
        }
    }
}
