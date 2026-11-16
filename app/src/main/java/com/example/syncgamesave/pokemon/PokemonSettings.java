package com.example.syncgamesave.pokemon;

import android.content.Context;

public final class PokemonSettings {
    public static final int DEFAULT_RETRIES = 2;
    public static final int MAX_RETRIES = 10;

    private static final String PREFS = "pokemon_tool";
    private static final String KEY_RETRIES = "retry_count";

    private PokemonSettings() { }

    /** How many more times to try with a different image treatment after a failed recognition. */
    public static int retries(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RETRIES, DEFAULT_RETRIES);
        return Math.max(0, Math.min(MAX_RETRIES, value));
    }

    public static void setRetries(Context context, int value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_RETRIES, Math.max(0, Math.min(MAX_RETRIES, value))).apply();
    }
}
