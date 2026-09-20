package com.foobnix.pdf.search.activity;

import android.app.Activity;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LiveSpeechSessionTest {
    private static class Clock implements LiveSpeechSession.Scheduler {
        private static class Task {
            Runnable action; long due;
            Task(Runnable action, long due) { this.action = action; this.due = due; }
        }
        long now;
        List<Task> tasks = new ArrayList<>();
        public void postDelayed(Runnable task, long delay) { tasks.add(new Task(task, now + delay)); }
        public void remove(Runnable task) { tasks.removeIf(t -> t.action == task); }
        public void clear() { tasks.clear(); }
        void advance(long millis) {
            long end = now + millis;
            while (true) {
                Task next = tasks.stream().min(Comparator.comparingLong(t -> t.due)).orElse(null);
                if (next == null || next.due > end) break;
                tasks.remove(next); now = next.due; next.action.run();
            }
            now = end;
        }
    }

    private static class Provider implements LiveSpeechSession.Provider {
        List<RecognitionListener> listeners = new ArrayList<>();
        int closed;
        public boolean available() { return true; }
        public LiveSpeechSession.Recognition create(RecognitionListener listener) {
            listeners.add(listener);
            return new LiveSpeechSession.Recognition() {
                public void start() { }
                public void close() {
                    closed++;
                    listener.onError(SpeechRecognizer.ERROR_CLIENT); // Providers may callback during cancellation.
                }
            };
        }
        RecognitionListener latest() { return listeners.get(listeners.size() - 1); }
    }

    private final Clock clock = new Clock();
    private final Provider provider = new Provider();
    private final List<String> transcripts = new ArrayList<>();
    private final LiveSpeechSession session = new LiveSpeechSession(mock(Activity.class),
            new LiveSpeechSession.Callback() {
                public void transcript(List<String> alternatives) { transcripts.add(alternatives.get(0)); }
                public void status(String message, boolean listening) { }
            }, provider, clock);

    private Bundle result(String text) {
        Bundle bundle = mock(Bundle.class);
        when(bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                .thenReturn(new ArrayList<>(Arrays.asList(text)));
        return bundle;
    }

    @Test public void stoppingRejectsLateCallbacksAndCancelsScheduledWork() {
        session.start();
        RecognitionListener old = provider.latest();
        session.stop();
        old.onPartialResults(result("the old wooden bridge"));
        old.onResults(result("the old wooden bridge"));
        old.onError(SpeechRecognizer.ERROR_NETWORK);
        clock.advance(150000);
        assertTrue(transcripts.isEmpty());
        assertFalse(session.isListening());
        assertEquals(1, provider.listeners.size());
        assertEquals(1, provider.closed);
    }

    @Test public void stoppingDuringBackoffDoesNotRestartMicrophone() {
        session.start();
        provider.latest().onError(SpeechRecognizer.ERROR_NETWORK);
        session.stop();
        clock.advance(5000);
        assertEquals(1, provider.listeners.size());
        assertFalse(session.isListening());
    }

    @Test public void silentProviderTriggersWatchdogAndBoundedRetry() {
        session.start();
        clock.advance(30000);
        assertEquals(1, provider.closed);
        clock.advance(500);
        assertEquals(2, provider.listeners.size());
        clock.advance(120000);
        assertFalse(session.isListening());
        assertTrue(provider.listeners.size() <= 4);
    }

    @Test public void activePartialsCannotExtendTwoMinuteLimit() {
        session.start();
        for (int i = 0; i < 5; i++) {
            clock.advance(20000);
            provider.latest().onPartialResults(result("爱丽丝坐在河边陪着姐姐看书"));
        }
        assertTrue(session.isListening());
        clock.advance(20000);
        assertFalse(session.isListening());
        assertEquals(1, provider.closed);
    }

    @Test public void finalRestartsWithRollingChineseContextAndIgnoresDuplicateFinal() {
        session.start();
        RecognitionListener first = provider.latest();
        first.onResults(result("爱丽丝坐在河边陪着姐姐看书"));
        first.onResults(result("不应接受的迟到结果"));
        clock.advance(350);
        assertEquals(2, provider.listeners.size());
        provider.latest().onPartialResults(result("忽然一只白兔从她身边跑过"));
        assertEquals(2, transcripts.size());
        assertTrue(transcripts.get(1).endsWith("忽然一只白兔从她身边跑过"));
        assertEquals(20, PassageMatcher.tokens(transcripts.get(1)).size());
        session.stop();
    }
}
