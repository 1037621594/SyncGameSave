package com.example.syncgamesave.translate;

import com.google.mlkit.nl.translate.TranslateLanguage;
import lombok.Getter;

/** Source language of the text to translate. The target is always Chinese. */
@Getter
public enum SourceLanguage {
    AUTO("自动", null),
    JAPANESE("日文", TranslateLanguage.JAPANESE),
    ENGLISH("英文", TranslateLanguage.ENGLISH);

    private final String label;
    /** ML Kit language code, null for AUTO. */
    private final String code;

    SourceLanguage(String label, String code) {
        this.label = label;
        this.code = code;
    }

    public SourceLanguage next() {
        SourceLanguage[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * Decides which language a block of text is in. Returns null when the text has nothing to translate
     * in the wanted language, such as digits or symbols only.
     */
    public static SourceLanguage resolve(String text, SourceLanguage mode) {
        boolean kana = false;
        boolean han = false;
        boolean latin = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0xFF66 && c <= 0xFF9F)) kana = true;
            else if (c >= 0x4E00 && c <= 0x9FFF) han = true;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin = true;
        }
        if (mode == JAPANESE) return kana || han ? JAPANESE : null;
        if (mode == ENGLISH) return latin ? ENGLISH : null;
        if (kana) return JAPANESE;
        if (latin) return ENGLISH;
        return han ? JAPANESE : null;
    }
}
