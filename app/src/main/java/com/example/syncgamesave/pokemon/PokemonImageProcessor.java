package com.example.syncgamesave.pokemon;

import android.graphics.Bitmap;
import java.util.Arrays;

/**
 * Makes small, low-contrast game text easier to read: upscale, stretch contrast, sharpen or binarize.
 * Each attempt index selects a different treatment, so a retry is not a repeat of the same input.
 */
public final class PokemonImageProcessor {
    private static final class Variant {
        final int minSide;
        final boolean sharpen;
        final boolean binarize;
        final boolean invert;

        Variant(int minSide, boolean sharpen, boolean binarize, boolean invert) {
            this.minSide = minSide;
            this.sharpen = sharpen;
            this.binarize = binarize;
            this.invert = invert;
        }
    }

    private static final Variant[] VARIANTS = {
            new Variant(96, true, false, false),
            new Variant(160, false, true, false),
            new Variant(160, false, true, true),
            new Variant(240, true, false, false),
            new Variant(240, false, true, false),
            new Variant(240, false, true, true),
    };

    /** Number of distinct treatments, which is also the most attempts that make sense. */
    public static final int MAX_ATTEMPTS = VARIANTS.length;
    private static final int MAX_PIXELS = 3_000_000;
    private static final int MAX_SCALE = 8;

    private PokemonImageProcessor() { }

    public static Bitmap process(Bitmap source, int attempt) {
        Variant variant = VARIANTS[Math.max(0, Math.min(attempt, VARIANTS.length - 1))];
        Bitmap scaled = scale(source, variant.minSide);
        int width = scaled.getWidth();
        int height = scaled.getHeight();
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != source) scaled.recycle();

        int[] gray = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            gray[i] = (299 * ((p >> 16) & 0xFF) + 587 * ((p >> 8) & 0xFF) + 114 * (p & 0xFF)) / 1000;
        }
        stretch(gray);

        // Text that touches the image edge is read poorly, so add a margin in the background color.
        int background = mostCommon(gray);
        int pad = Math.max(8, Math.min(width, height) / 6);
        int paddedWidth = width + 2 * pad;
        int paddedHeight = height + 2 * pad;
        int[] padded = new int[paddedWidth * paddedHeight];
        Arrays.fill(padded, background);
        for (int y = 0; y < height; y++)
            System.arraycopy(gray, y * width, padded, (y + pad) * paddedWidth + pad, width);

        if (variant.sharpen) padded = sharpen(padded, paddedWidth, paddedHeight);
        if (variant.binarize) {
            int threshold = otsu(padded);
            for (int i = 0; i < padded.length; i++) padded[i] = padded[i] > threshold ? 255 : 0;
        }
        if (variant.invert) for (int i = 0; i < padded.length; i++) padded[i] = 255 - padded[i];

        int[] out = new int[padded.length];
        for (int i = 0; i < padded.length; i++) out[i] = 0xFF000000 | (padded[i] << 16) | (padded[i] << 8) | padded[i];
        Bitmap result = Bitmap.createBitmap(paddedWidth, paddedHeight, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, paddedWidth, 0, 0, paddedWidth, paddedHeight);
        return result;
    }

    private static Bitmap scale(Bitmap source, int minSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int shortSide = Math.min(width, height);
        double factor = shortSide >= minSide ? 1.0 : Math.min((double) minSide / shortSide, MAX_SCALE);
        factor = Math.min(factor, Math.sqrt((double) MAX_PIXELS / ((double) width * height)));
        if (factor <= 1.0) return source;
        return Bitmap.createScaledBitmap(source, Math.max(1, (int) Math.round(width * factor)),
                Math.max(1, (int) Math.round(height * factor)), true);
    }

    /** Spreads the 1st to 99th percentile of brightness over the full range. */
    private static void stretch(int[] gray) {
        int[] histogram = new int[256];
        for (int value : gray) histogram[value]++;
        int cut = gray.length / 100;
        int low = 0;
        int count = 0;
        for (; low < 255; low++) {
            count += histogram[low];
            if (count > cut) break;
        }
        int high = 255;
        count = 0;
        for (; high > 0; high--) {
            count += histogram[high];
            if (count > cut) break;
        }
        if (high - low < 16) return;
        for (int i = 0; i < gray.length; i++)
            gray[i] = Math.max(0, Math.min(255, (gray[i] - low) * 255 / (high - low)));
    }

    private static int mostCommon(int[] gray) {
        int[] histogram = new int[256];
        for (int value : gray) histogram[value]++;
        int best = 0;
        for (int i = 1; i < 256; i++) if (histogram[i] > histogram[best]) best = i;
        return best;
    }

    private static int[] sharpen(int[] gray, int width, int height) {
        int[] out = gray.clone();
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                int value = 5 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width];
                out[i] = Math.max(0, Math.min(255, value));
            }
        }
        return out;
    }

    /** Picks the brightness threshold that best separates text from background. */
    private static int otsu(int[] gray) {
        int[] histogram = new int[256];
        for (int value : gray) histogram[value]++;
        long total = gray.length;
        long sum = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * histogram[i];
        long sumBackground = 0;
        long weightBackground = 0;
        double best = -1;
        int threshold = 127;
        for (int t = 0; t < 256; t++) {
            weightBackground += histogram[t];
            if (weightBackground == 0) continue;
            long weightForeground = total - weightBackground;
            if (weightForeground == 0) break;
            sumBackground += (long) t * histogram[t];
            double meanBackground = (double) sumBackground / weightBackground;
            double meanForeground = (double) (sum - sumBackground) / weightForeground;
            double between = (double) weightBackground * weightForeground
                    * (meanBackground - meanForeground) * (meanBackground - meanForeground);
            if (between > best) {
                best = between;
                threshold = t;
            }
        }
        return threshold;
    }
}
