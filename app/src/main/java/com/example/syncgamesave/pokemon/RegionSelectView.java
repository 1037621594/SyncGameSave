package com.example.syncgamesave.pokemon;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Shows a frozen screenshot and lets the user drag a rectangle over it. */
public class RegionSelectView extends View {
    public interface Listener {
        /** Reports the region as fractions (0..1) of the view, so it maps onto any bitmap size. */
        void onSelected(RectF fractions);
    }

    private final Bitmap frame;
    private final Listener listener;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect whole = new Rect();
    private final RectF region = new RectF();
    private final float minSize;
    private float startX;
    private float startY;
    private boolean hasRegion;

    public RegionSelectView(Context context, Bitmap frame, Listener listener) {
        super(context);
        this.frame = frame;
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        minSize = 24 * density;
        dimPaint.setColor(0x99000000);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        borderPaint.setColor(0xFFFFD54F);
        hintPaint.setColor(Color.WHITE);
        hintPaint.setTextSize(18 * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setShadowLayer(4 * density, 0, 0, Color.BLACK);
    }

    @Override protected void onDraw(Canvas canvas) {
        whole.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(frame, null, whole, bitmapPaint);
        canvas.drawRect(whole, dimPaint);
        if (hasRegion) {
            canvas.save();
            canvas.clipRect(region);
            canvas.drawBitmap(frame, null, whole, bitmapPaint);
            canvas.restore();
            canvas.drawRect(region, borderPaint);
        } else {
            canvas.drawText("拖动手指圈出宝可梦名字所在区域", getWidth() / 2f, getHeight() / 2f, hintPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = clamp(event.getX(), 0, getWidth());
        float y = clamp(event.getY(), 0, getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = x;
                startY = y;
                hasRegion = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                updateRegion(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                updateRegion(x, y);
                if (region.width() >= minSize && region.height() >= minSize) {
                    listener.onSelected(new RectF(region.left / getWidth(), region.top / getHeight(),
                            region.right / getWidth(), region.bottom / getHeight()));
                } else {
                    hasRegion = false;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                hasRegion = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateRegion(float x, float y) {
        region.set(Math.min(startX, x), Math.min(startY, y), Math.max(startX, x), Math.max(startY, y));
        hasRegion = true;
        invalidate();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
