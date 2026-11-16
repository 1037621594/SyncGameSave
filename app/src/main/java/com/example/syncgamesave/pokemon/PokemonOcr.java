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

    private final TextRecognizer recognizer =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    public void recognize(Bitmap source, Callback callback) {
        recognizer.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener(text -> {
                    List<String> lines = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks())
                        for (Text.Line line : block.getLines()) lines.add(line.getText());
                    callback.onResult(lines);
                })
                .addOnFailureListener(callback::onError);
    }

    public void close() { recognizer.close(); }
}
