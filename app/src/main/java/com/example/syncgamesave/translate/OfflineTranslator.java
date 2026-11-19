package com.example.syncgamesave.translate;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Japanese and English to Chinese with ML Kit on-device translation. The language models are downloaded
 * once on first use; after that translation needs no network. All callbacks arrive on the main thread.
 */
public class OfflineTranslator {
    public interface Callback {
        /** The model for this language is missing and is being downloaded. */
        void onDownloading(String languageLabel);
        /** Every block that could be translated now has its translated text set. */
        void onDone(List<ScreenText> texts);
        void onError(Exception error);
    }

    private final Map<SourceLanguage, Translator> translators = new EnumMap<>(SourceLanguage.class);

    public void translate(List<ScreenText> texts, SourceLanguage mode, Callback callback) {
        Map<SourceLanguage, List<ScreenText>> groups = new EnumMap<>(SourceLanguage.class);
        int total = 0;
        for (ScreenText item : texts) {
            SourceLanguage language = SourceLanguage.resolve(item.getText(), mode);
            if (language == null) continue;
            List<ScreenText> group = groups.get(language);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(language, group);
            }
            group.add(item);
            total++;
        }
        Job job = new Job(texts, total, callback);
        if (total == 0) {
            callback.onDone(texts);
            return;
        }
        for (Map.Entry<SourceLanguage, List<ScreenText>> entry : groups.entrySet())
            translateGroup(entry.getKey(), entry.getValue(), job);
    }

    public void close() {
        for (Translator translator : translators.values()) translator.close();
        translators.clear();
    }

    private void translateGroup(SourceLanguage language, List<ScreenText> items, Job job) {
        Translator translator = translators.get(language);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(language.getCode())
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build());
            translators.put(language, translator);
        }
        Translator active = translator;
        RemoteModelManager.getInstance()
                .isModelDownloaded(new TranslateRemoteModel.Builder(language.getCode()).build())
                .addOnSuccessListener(downloaded -> {
                    if (!downloaded && !job.failed) job.callback.onDownloading(language.getLabel());
                    active.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                            .addOnSuccessListener(unused -> translateItems(active, language, items, job))
                            .addOnFailureListener(job::fail);
                })
                .addOnFailureListener(job::fail);
    }

    private void translateItems(Translator translator, SourceLanguage language, List<ScreenText> items, Job job) {
        for (ScreenText item : items) {
            translator.translate(clean(item.getText(), language))
                    .addOnSuccessListener(result -> {
                        item.setTranslated(result);
                        job.itemDone();
                    })
                    .addOnFailureListener(job::fail);
        }
    }

    /** Joins the lines of a block: Japanese has no spaces between words, English needs one. */
    private static String clean(String text, SourceLanguage language) {
        return text.replaceAll("\\s*\\n\\s*", language == SourceLanguage.ENGLISH ? " " : "").trim();
    }

    /** Counts finished blocks across language groups and reports the outcome once. */
    private static class Job {
        private final List<ScreenText> texts;
        private final Callback callback;
        private int remaining;
        private boolean failed;

        Job(List<ScreenText> texts, int remaining, Callback callback) {
            this.texts = texts;
            this.remaining = remaining;
            this.callback = callback;
        }

        void itemDone() {
            if (failed) return;
            if (--remaining == 0) callback.onDone(texts);
        }

        void fail(Exception error) {
            if (failed) return;
            failed = true;
            callback.onError(error);
        }
    }
}
