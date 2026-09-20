package com.foobnix.pdf.search.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.foobnix.pdf.info.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main-thread adapter around the installed Android recognition service. */
public final class LiveSpeechSession {
    public interface Callback {
        void transcript(List<String> alternatives);
        void status(String message, boolean listening);
    }
    private final Activity activity;
    private final Callback callback;
    interface Scheduler {
        void postDelayed(Runnable task, long delay);
        void remove(Runnable task);
        void clear();
    }
    interface Recognition {
        void start();
        void close();
    }
    interface Provider {
        boolean available();
        Recognition create(RecognitionListener listener);
    }
    private final Scheduler scheduler;
    private final Provider provider;
    private Recognition recognizer;
    private boolean active;
    private int generation;
    private int retries;
    private String completed = "";

    public LiveSpeechSession(Activity activity, Callback callback) {
        this(activity, callback, new AndroidProvider(activity), new MainScheduler());
    }

    LiveSpeechSession(Activity activity, Callback callback, Provider provider, Scheduler scheduler) {
        this.activity = activity;
        this.callback = callback;
        this.provider = provider;
        this.scheduler = scheduler;
    }

    private static final class MainScheduler implements Scheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());
        public void postDelayed(Runnable task, long delay) { handler.postDelayed(task, delay); }
        public void remove(Runnable task) { handler.removeCallbacks(task); }
        public void clear() { handler.removeCallbacksAndMessages(null); }
    }

    private static final class AndroidProvider implements Provider {
        private final Activity activity;
        AndroidProvider(Activity activity) { this.activity = activity; }
        public boolean available() { return SpeechRecognizer.isRecognitionAvailable(activity); }
        public Recognition create(RecognitionListener listener) {
            SpeechRecognizer speech = SpeechRecognizer.createSpeechRecognizer(activity);
            speech.setRecognitionListener(listener);
            return new Recognition() {
                public void start() {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                    speech.startListening(intent);
                }
                public void close() {
                    try { speech.cancel(); }
                    finally { speech.destroy(); }
                }
            };
        }
    }

    public boolean isListening() { return active; }

    public void start() {
        stop();
        if (!provider.available()) {
            callback.status(activity.getString(R.string.voice_search_unavailable), false);
            return;
        }
        active = true;
        retries = 0;
        completed = "";
        scheduler.postDelayed(() -> {
            if (active) {
                stop();
                callback.status(activity.getString(R.string.voice_search_session_ended), false);
            }
        }, 120000);
        begin();
    }

    public void stop() {
        active = false;
        generation++;
        scheduler.clear();
        release();
    }

    private void release() {
        if (recognizer != null) {
            Recognition previous = recognizer;
            recognizer = null;
            previous.close();
        }
    }

    private void begin() {
        if (!active) return;
        int session = ++generation;
        release();
        callback.status(activity.getString(R.string.voice_search_listening), true);
        try {
            Runnable watchdog = () -> {
                if (active && session == generation) retry(-1, "provider callback timeout");
            };
            recognizer = provider.create(new RecognitionListener() {
                private boolean current() { return active && session == generation; }
                private void heard(Bundle bundle, boolean complete) {
                    if (!current() || bundle == null) return;
                    ArrayList<String> results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (results == null || results.isEmpty()) return;
                    List<String> alternatives = new ArrayList<>();
                    for (String result : results) {
                        if (result == null || result.trim().isEmpty()) continue;
                        alternatives.add(PassageMatcher.tail(completed + " " + result, 20));
                    }
                    if (alternatives.isEmpty()) return;
                    scheduler.remove(watchdog);
                    if (!complete) scheduler.postDelayed(watchdog, 30000);
                    Log.i("BookSpeech", (complete ? "final" : "partial") + " words="
                            + PassageMatcher.tokens(alternatives.get(0)).size());
                    callback.transcript(alternatives);
                    if (complete) completed = alternatives.get(0);
                }
                public void onReadyForSpeech(Bundle params) {
                    if (current()) Log.i("BookSpeech", "ready");
                }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float rms) { }
                public void onBufferReceived(byte[] buffer) { }
                public void onEndOfSpeech() { }
                public void onError(int error) {
                    scheduler.remove(watchdog);
                    if (current()) retry(error, errorName(error));
                }
                public void onResults(Bundle results) {
                    scheduler.remove(watchdog);
                    if (!current()) return;
                    heard(results, true);
                    // Recognition sessions may end at pauses; keep the same rolling context.
                    if (current()) {
                        generation++; // A final callback ends this provider session immediately.
                        release();
                        scheduler.postDelayed(LiveSpeechSession.this::begin, 350);
                    }
                }
                public void onPartialResults(Bundle results) { heard(results, false); }
                public void onEvent(int type, Bundle params) { }
            });
            recognizer.start();
            scheduler.postDelayed(watchdog, 30000);
        } catch (RuntimeException failure) {
            Log.e("BookSpeech", "Cannot start provider", failure);
            retry(SpeechRecognizer.ERROR_CLIENT, errorName(SpeechRecognizer.ERROR_CLIENT));
        }
    }

    private void retry(int error, String reason) {
        Log.w("BookSpeech", "error code=" + error + " name=" + reason + " retry=" + retries);
        generation++;
        release();
        boolean recoverable = error == -1 || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_AUDIO
                || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED;
        if (active && recoverable && retries < 3) {
            int attempt = ++retries;
            callback.status(activity.getString(R.string.voice_search_retry, reason, error, attempt), true);
            scheduler.postDelayed(this::begin, 500L << (attempt - 1));
        } else {
            stop();
            callback.status(activity.getString(R.string.voice_search_error, reason, error), false);
        }
    }

    public static String errorName(int code) {
        switch (code) {
            case 1: return "NETWORK_TIMEOUT";
            case 2: return "NETWORK";
            case 3: return "AUDIO";
            case 4: return "SERVER";
            case 5: return "CLIENT";
            case 6: return "SPEECH_TIMEOUT";
            case 7: return "NO_MATCH";
            case 8: return "RECOGNIZER_BUSY";
            case 9: return "INSUFFICIENT_PERMISSIONS";
            case 10: return "TOO_MANY_REQUESTS";
            case 11: return "SERVER_DISCONNECTED";
            case 12: return "LANGUAGE_NOT_SUPPORTED";
            case 13: return "LANGUAGE_UNAVAILABLE";
            default: return "UNKNOWN";
        }
    }
}
