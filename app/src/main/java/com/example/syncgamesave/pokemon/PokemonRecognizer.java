package com.example.syncgamesave.pokemon;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Runs OCR and name matching, retrying with a different image treatment until a Pokemon is found. */
public class PokemonRecognizer {
    public interface Callback {
        /** Called on the main thread before each attempt, numbered from 1. */
        void onAttempt(int attempt, int total);

        /** {@code candidates} is empty when every attempt failed to match a name. */
        void onResult(List<String> lines, List<MatchCandidate> candidates, int attempts);

        void onError(Exception error);
    }

    private final PokemonOcr ocr = new PokemonOcr();
    private final PokemonMatcher matcher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    public PokemonRecognizer(PokemonMatcher matcher) {
        this.matcher = matcher;
    }

    public void recognize(Bitmap crop, int retries, Callback callback) {
        int total = Math.max(1, Math.min(retries, PokemonSettings.MAX_RETRIES) + 1);
        attempt(crop, 0, total, new LinkedHashSet<>(), callback);
    }

    public void close() {
        closed = true;
        executor.shutdownNow();
        ocr.close();
    }

    private void attempt(Bitmap crop, int index, int total, Set<String> seen, Callback callback) {
        if (closed) return;
        callback.onAttempt(index + 1, total);
        try {
            executor.execute(() -> {
                Bitmap processed;
                try {
                    processed = PokemonImageProcessor.process(crop, index);
                } catch (RuntimeException | OutOfMemoryError e) {
                    main.post(() -> {
                        if (!closed) callback.onError(new IllegalStateException("图像处理失败，请重试"));
                    });
                    return;
                }
                main.post(() -> recognizeProcessed(crop, processed, index, total, seen, callback));
            });
        } catch (RejectedExecutionException ignored) {
            // The recognizer was closed while the request was being queued.
        }
    }

    private void recognizeProcessed(Bitmap crop, Bitmap processed, int index, int total, Set<String> seen,
                                    Callback callback) {
        if (closed) return;
        ocr.recognize(processed, new PokemonOcr.Callback() {
            @Override public void onResult(List<String> lines) {
                if (closed) return;
                seen.addAll(lines);
                List<MatchCandidate> found = matcher.match(lines);
                if (!found.isEmpty() || index + 1 >= total)
                    callback.onResult(new ArrayList<>(seen), found, index + 1);
                else attempt(crop, index + 1, total, seen, callback);
            }

            @Override public void onError(Exception error) {
                if (!closed) callback.onError(error);
            }
        });
    }
}
