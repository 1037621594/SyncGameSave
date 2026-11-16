package com.example.syncgamesave.pokemon;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.WindowManager;
import java.nio.ByteBuffer;

/**
 * Keeps one virtual display alive and hands out the newest frame on request. Android 14 allows only one
 * virtual display per projection token, so the display is created once and reused for every capture.
 */
public class ScreenCapture implements FrameSource {
    /** Lets overlay windows that were hidden by the caller disappear from the stream before grabbing a frame. */
    private static final long SETTLE_MS = 150;
    private static final long TIMEOUT_MS = 900;
    private static final int MAX_IMAGES = 3;

    private final MediaProjection projection;
    private final WindowManager windowManager;
    private final int densityDpi;
    private final HandlerThread thread = new HandlerThread("pokemon-capture");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private ImageReader reader;
    private VirtualDisplay display;
    private Image held;
    private Callback pending;
    private boolean awaiting;
    private boolean released;
    private long requestId;

    public ScreenCapture(Context context, MediaProjection projection) {
        this.projection = projection;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        densityDpi = context.getResources().getDisplayMetrics().densityDpi;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @SuppressWarnings("deprecation")
    public static Point realSize(WindowManager windowManager) {
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        return size;
    }

    public void start() {
        Point size = realSize(windowManager);
        synchronized (lock) {
            reader = newReader(size);
            display = projection.createVirtualDisplay("pokemon-capture", size.x, size.y, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
            if (display == null) throw new IllegalStateException("无法创建录屏显示");
        }
    }

    /** Re-creates the frame buffer after a rotation. */
    public void resize() {
        Point size = realSize(windowManager);
        synchronized (lock) {
            if (released || display == null) return;
            closeHeldLocked();
            ImageReader old = reader;
            reader = newReader(size);
            display.resize(size.x, size.y, densityDpi);
            display.setSurface(reader.getSurface());
            old.close();
        }
    }

    /** Delivers a frame taken after the caller's own overlays were hidden. */
    public void capture(Callback callback) {
        final long id;
        synchronized (lock) {
            id = ++requestId;
            pending = callback;
            awaiting = false;
        }
        main.postDelayed(() -> {
            synchronized (lock) {
                if (requestId == id && pending != null) awaiting = true;
            }
        }, SETTLE_MS);
        // A static screen produces no new frame, so fall back to the newest one after a timeout.
        main.postDelayed(() -> worker.post(() -> {
            synchronized (lock) {
                if (requestId == id && pending != null) deliverLocked();
            }
        }), TIMEOUT_MS);
    }

    public void release() {
        synchronized (lock) {
            released = true;
            pending = null;
            closeHeldLocked();
            if (display != null) display.release();
            if (reader != null) reader.close();
        }
        main.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private ImageReader newReader(Point size) {
        ImageReader created = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, MAX_IMAGES);
        created.setOnImageAvailableListener(this::onImage, worker);
        return created;
    }

    private void onImage(ImageReader source) {
        synchronized (lock) {
            Image image;
            try { image = source.acquireLatestImage(); }
            catch (IllegalStateException e) { return; }
            if (image == null) return;
            if (released || source != reader) {
                image.close();
                return;
            }
            if (held != null) held.close();
            held = image;
            if (awaiting && pending != null) deliverLocked();
        }
    }

    private void deliverLocked() {
        Callback callback = pending;
        pending = null;
        awaiting = false;
        Bitmap bitmap = null;
        if (held != null) {
            try { bitmap = toBitmap(held); }
            catch (RuntimeException | OutOfMemoryError e) { bitmap = null; }
        }
        Bitmap result = bitmap;
        main.post(() -> {
            if (result == null) callback.onError("截屏失败，请重试");
            else callback.onFrame(result);
        });
    }

    private void closeHeldLocked() {
        if (held != null) {
            held.close();
            held = null;
        }
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int paddedWidth = plane.getRowStride() / plane.getPixelStride();
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) return padded;
        Bitmap exact = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return exact;
    }
}
