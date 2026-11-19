package com.example.syncgamesave.translate;

import android.graphics.Rect;
import lombok.AllArgsConstructor;
import lombok.Data;

/** One block of recognized text, its position in screen pixels and, once translated, the Chinese text. */
@Data
@AllArgsConstructor
public class ScreenText {
    private String text;
    private Rect box;
    private int lineCount;
    /** Null until translated. */
    private String translated;
}
