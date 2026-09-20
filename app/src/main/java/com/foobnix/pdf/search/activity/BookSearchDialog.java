package com.foobnix.pdf.search.activity;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.TextView;

import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.android.utils.Views;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.DragingPopup;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.sys.TempHolder;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the live listening UI, throttling and cancellation, using the shared book search service. */
public final class BookSearchDialog {
    private static String lastText = "";

    public static void rememberQuery(String text) { lastText = text; }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger revision = new AtomicInteger();
    private final LiveMatchPolicy policy = new LiveMatchPolicy();
    private final DocumentController controller;
    private final FrameLayout anchor;
    private LiveSpeechSession speech;
    private boolean closed;
    private long lastSearch;
    private String lastTranscript = "";
    private EditText input;
    private TextView status, searching;
    private Button listen;
    private GridView grid;
    private View progress;
    private com.foobnix.android.utils.BaseItemLayoutAdapter<PassageMatcher.Match> adapter;

    private BookSearchDialog(FrameLayout anchor, DocumentController controller) {
        this.anchor = anchor; this.controller = controller;
    }

    public static void show(FrameLayout anchor, DocumentController controller, String text, List<String> alternatives) {
        new BookSearchDialog(anchor, controller).open(text, alternatives);
    }

    private void open(String text, List<String> alternatives) {
        DragingPopup popup = new DragingPopup(R.string.search, anchor, 340, 320) {
            @Override public View getContentView(LayoutInflater inflater) {
                View view = inflater.inflate(R.layout.search_dialog, null, false);
                input = view.findViewById(R.id.edit1);
                input.setText(text.isEmpty() ? lastText : text);
                status = view.findViewById(R.id.speechStatus);
                searching = view.findViewById(R.id.searching);
                listen = view.findViewById(R.id.onVoiceSearch);
                progress = view.findViewById(R.id.progressBarSearch);
                grid = view.findViewById(R.id.grid1);
                adapter = new com.foobnix.android.utils.BaseItemLayoutAdapter<PassageMatcher.Match>(
                        anchor.getContext(), R.layout.book_search_result) {
                    @Override public void populateView(View row, int position, PassageMatcher.Match match) {
                        Views.text(row, android.R.id.text1, anchor.getContext().getString(R.string.book_search_page, TxtUtils.deltaPage(controller.searchPageMapping().displayedPage(match.page) + 1, 0)));
                        TextView preview = Views.text(row, android.R.id.text2, match.preview);
                        preview.setMaxLines(3);
                    }
                    @Override public long getItemId(int position) {
                        return controller.searchPageMapping().displayedPage(getItem(position).page) + 1;
                    }
                };
                grid.setAdapter(adapter);
                grid.setOnItemClickListener((parent, item, position, id) -> {
                    PassageMatcher.Match match = adapter.getItem(position);
                    closeDialog(); // Also cancels listening and pending search callbacks.
                    if (match != null) controller.highlightMatch(match.page, match.queryTokens);
                    controller.onGoToPage((int) id);
                });
                speech = new LiveSpeechSession(controller.getActivity(), new LiveSpeechSession.Callback() {
                    public void transcript(List<String> alternatives) { heard(alternatives); }
                    public void status(String message, boolean listening) {
                        if (closed) return;
                        status.setVisibility(View.VISIBLE);
                        status.setText(message);
                        showListening(listening);
                    }
                });
                controller.setListeningStop(BookSearchDialog.this::stop);
                listen.setOnClickListener(v -> {
                    if (speech.isListening()) stop();
                    else controller.requestSearchMicrophone(() -> {
                        if (closed) return;
                        revision.incrementAndGet();
                        handler.removeCallbacksAndMessages(null);
                        policy.reset();
                        lastTranscript = "";
                        input.setText("");
                        adapter.getItems().clear(); adapter.notifyDataSetChanged();
                        grid.setVisibility(View.GONE);
                        searching.setVisibility(View.GONE);
                        Keyboards.close(input);
                        speech.start();
                    });
                });
                view.findViewById(R.id.onSearch).setOnClickListener(v -> typed());
                input.setOnEditorActionListener((v, action, event) -> { typed(); return true; });
                view.findViewById(R.id.imageClear).setOnClickListener(v -> {
                    stop();
                    input.setText(""); lastText = "";
                    grid.setVisibility(View.GONE);
                    status.setVisibility(View.GONE);
                    adapter.getItems().clear(); adapter.notifyDataSetChanged();
                    searching.setVisibility(View.GONE);
                    controller.clearSelectedText();
                });
                if (!alternatives.isEmpty()) handler.post(() -> heard(alternatives));
                return view;
            }
        };
        popup.setOnCloseListener(() -> {
            closed = true;
            stop();
        });
        // Rebuild every time: a cached Find view used to discard returned transcripts.
        popup.show("searchMenuLiveCompact", true);
    }

    private void showListening(boolean listening) {
        listen.setText(listening ? R.string.voice_search_stop : R.string.voice_search_idle);
        android.graphics.drawable.Drawable icon = anchor.getContext().getDrawable(
                listening ? R.drawable.glyphicons_176_stop : R.drawable.ic_book_microphone).mutate();
        icon.setTintList(listen.getTextColors());
        int size = com.foobnix.android.utils.Dips.dpToPx(24);
        icon.setBounds(0, 0, size, size);
        listen.setCompoundDrawablesRelative(icon, null, null, null);
        input.setHint(listening ? R.string.voice_search_waiting : R.string.book_search_hint);
    }

    private void stop() {
        revision.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        policy.reset();
        TempHolder.isSeaching = false;
        if (speech != null) speech.stop();
        if (listen != null) showListening(false);
        if (searching != null) searching.setVisibility(View.GONE);
        if (progress != null) progress.setVisibility(View.GONE);
        if (status != null) status.setText(R.string.voice_search_stopped);
    }

    private void heard(List<String> alternatives) {
        if (closed || alternatives.isEmpty()) return;
        String text = alternatives.get(0);
        if (text.equals(lastTranscript)) return;
        lastTranscript = text;
        input.setText(text);
        input.setSelection(input.length());
        int ticket = revision.incrementAndGet(); // Invalidate in-flight results immediately, even before the next launch.
        handler.removeCallbacksAndMessages(null);
        if (PassageMatcher.tokens(text).size() < 2) return;
        long delay = Math.max(0, lastSearch + 400 - SystemClock.uptimeMillis());
        handler.postDelayed(() -> search(alternatives, true, 0, controller.getPageCount(), ticket), delay);
    }

    private void typed() {
        stop();
        status.setVisibility(View.GONE);
        String text = input.getText().toString().trim();
        int first = 1, last = controller.getPageCount();
        Matcher range = Pattern.compile("(?s)(.*?)\\[(\\d+):(\\d+)\\]$").matcher(text);
        if (range.matches()) {
            text = range.group(1).trim();
            try {
                first = Math.max(1, Integer.parseInt(range.group(2)));
                last = Math.min(last, Integer.parseInt(range.group(3)));
            } catch (NumberFormatException invalid) { first = 1; last = controller.getPageCount(); }
        }
        if (text.isEmpty()) return;
        if (last < first) { first = 1; last = controller.getPageCount(); }
        lastText = text;
        Keyboards.close(input);
        search(Collections.singletonList(text), false, first - 1, last, revision.incrementAndGet());
    }

    private void search(List<String> queries, boolean fuzzy, int first, int last, int ticket) {
        if (closed || ticket != revision.get()) return;
        lastSearch = SystemClock.uptimeMillis();
        TempHolder.isSeaching = true;
        // Fast live queries should not flash a spinner on every e-ink refresh.
        Runnable showBusy = () -> {
            if (closed || revision.get() != ticket) return;
            searching.setText(R.string.searching_please_wait_);
            searching.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
        };
        handler.postDelayed(showBusy, 250);
        controller.getBookSearch().search(queries, fuzzy,
                fuzzy ? 0 : controller.searchPageMapping().nativeFirst(first),
                fuzzy ? controller.getBookSearch().pageCount() : controller.searchPageMapping().nativeEnd(last),
                () -> closed || revision.get() != ticket, new BookSearch.Callback() {
                    public void progress(int completed, int total) {
                        searching.setText(controller.getActivity().getString(R.string.voice_search_indexing, completed, total));
                        searching.setVisibility(View.VISIBLE);
                        progress.setVisibility(View.VISIBLE);
                    }
                    public void complete(List<PassageMatcher.Match> matches) {
                        handler.removeCallbacks(showBusy);
                        TempHolder.isSeaching = false;
                        progress.setVisibility(View.GONE);
                        adapter.getItems().clear(); adapter.getItems().addAll(matches); adapter.notifyDataSetChanged();
                        grid.setVisibility(matches.isEmpty() ? View.GONE : View.VISIBLE);
                        searching.setText(matches.isEmpty() ? R.string.msg_no_text_found :
                                speech.isListening() ? R.string.voice_search_more_words : R.string.voice_search_choose_page);
                        searching.setVisibility(fuzzy || matches.isEmpty() ? View.VISIBLE : View.GONE);
                        if (fuzzy && speech.isListening() && policy.shouldJump(queries.get(0), matches)) {
                            int page = controller.searchPageMapping().displayedPage(matches.get(0).page) + 1;
                            android.util.Log.i("BookSpeech", "auto_jump page=" + page + " score=" + matches.get(0).score);
                            stop();
                            status.setText(controller.getActivity().getString(R.string.voice_search_found, page));
                            searching.setVisibility(View.GONE);
                            controller.highlightMatch(matches.get(0).page, matches.get(0).queryTokens);
                            controller.onGoToPage(page);
                        }
                    }
                    public void failed(Exception error) {
                        handler.removeCallbacks(showBusy);
                        searching.setVisibility(View.VISIBLE);
                        TempHolder.isSeaching = false;
                        progress.setVisibility(View.GONE);
                        searching.setText(R.string.book_index_failed);
                    }
                });
    }
}
