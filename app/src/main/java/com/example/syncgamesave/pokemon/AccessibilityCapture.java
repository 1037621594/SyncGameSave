package com.example.syncgamesave.pokemon;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import androidx.annotation.RequiresApi;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Takes one screenshot per request through the accessibility service, so nothing records the screen between taps. */
@RequiresApi(30)
public class AccessibilityCapture implements FrameSource {
    /** Lets the caller's own overlay windows disappear from the screen before the screenshot is taken. */
    private static final long SETTLE_MS = 200;
    /** The system rejects screenshots requested too close together, so retry after a short pause. */
    private static final long RETRY_MS = 400;
    private static final int MAX_RETRIES = 3;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean released;

    public AccessibilityCapture(AccessibilityService service) {
        this.service = service;
    }

    @Override public void capture(Callback callback) {
        main.postDelayed(() -> request(callback, 0), SETTLE_MS);
    }

    @Override public void resize() { }

    @Override public void release() {
        released = true;
        main.removeCallbacksAndMessages(null);
        executor.shutdown();
    }

    private void request(Callback callback, int attempt) {
        if (released) return;
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            Bitmap bitmap = toBitmap(result);
                            main.post(() -> {
                                if (released) return;
                                if (bitmap == null) callback.onError("截屏失败，请重试");
                                else callback.onFrame(bitmap);
                            });
                        }

                        @Override public void onFailure(int errorCode) {
                            main.post(() -> {
                                if (released) return;
                                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                        && attempt < MAX_RETRIES)
                                    main.postDelayed(() -> request(callback, attempt + 1), RETRY_MS);
                                else callback.onError("截屏失败（错误码 " + errorCode + "），请重试");
                            });
                        }
                    });
        } catch (RuntimeException e) {
            callback.onError("截屏失败：" + e.getMessage());
        }
    }

    /** Copies the hardware bitmap into memory, because text recognition cannot read hardware bitmaps. */
    private static Bitmap toBitmap(AccessibilityService.ScreenshotResult result) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        try {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) return null;
            Bitmap copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
            hardware.recycle();
            return copy;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            buffer.close();
        }
    }
}
