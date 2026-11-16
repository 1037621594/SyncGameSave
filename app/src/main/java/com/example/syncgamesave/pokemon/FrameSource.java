package com.example.syncgamesave.pokemon;

import android.graphics.Bitmap;

/** Supplies one screenshot per request, either from a screen recording or from the accessibility service. */
public interface FrameSource {
    interface Callback {
        void onFrame(Bitmap frame);
        void onError(String message);
    }

    /** Delivers a frame taken after the caller's own overlays were hidden. */
    void capture(Callback callback);

    /** Called after a rotation. */
    void resize();

    void release();
}
