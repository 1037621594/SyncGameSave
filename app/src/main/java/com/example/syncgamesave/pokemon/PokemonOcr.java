package com.example.syncgamesave.pokemon;

import android.graphics.Bitmap;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

/** Offline Chinese text recognition using the ML Kit model bundled in the APK. */
public class PokemonOcr {
    public interface Callback {
        void onResult(List<String> lines);
        void onError(Exception error);
    }

    private static final int MIN_SIDE = 96;
    private static final int MAX_SCALE = 4;

    private final TextRecognizer recognizer =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    public void recognize(Bitmap source, Callback callback) {
        recognizer.process(InputImage.fromBitmap(enlarge(source), 0))
                .addOnSuccessListener(text -> {
                    List<String> lines = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks())
                        for (Text.Line line : block.getLines()) lines.add(line.getText());
                    callback.onResult(lines);
                })
                .addOnFailureListener(callback::onError);
    }

    /** Small crops are scaled up because game text is often only a few pixels tall. */
    private static Bitmap enlarge(Bitmap source) {
        int shortSide = Math.min(source.getWidth(), source.getHeight());
        if (shortSide >= MIN_SIDE) return source;
        int scale = Math.min(MAX_SCALE, (MIN_SIDE + shortSide - 1) / shortSide);
        return Bitmap.createScaledBitmap(source, source.getWidth() * scale, source.getHeight() * scale, true);
    }

    public void close() { recognizer.close(); }
}
