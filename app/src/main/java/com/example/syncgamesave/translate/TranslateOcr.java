package com.example.syncgamesave.translate;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

/** Offline recognition of Japanese and Latin text with the ML Kit model bundled in the APK. */
public class TranslateOcr {
    public interface Callback {
        void onResult(List<ScreenText> texts);
        void onError(Exception error);
    }

    private final TextRecognizer recognizer =
            TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());

    /** Block positions are shifted by the offset, so they are relative to the full screenshot, not the crop. */
    public void recognize(Bitmap source, int offsetX, int offsetY, Callback callback) {
        recognizer.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener(text -> {
                    List<ScreenText> result = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks()) {
                        Rect box = block.getBoundingBox();
                        if (box == null || block.getText().trim().isEmpty()) continue;
                        Rect shifted = new Rect(box);
                        shifted.offset(offsetX, offsetY);
                        result.add(new ScreenText(block.getText(), shifted, Math.max(1, block.getLines().size()), null));
                    }
                    callback.onResult(result);
                })
                .addOnFailureListener(callback::onError);
    }

    public void close() { recognizer.close(); }
}
