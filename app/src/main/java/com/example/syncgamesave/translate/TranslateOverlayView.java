package com.example.syncgamesave.translate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Draws each Chinese translation over the original text, using an opaque box so the source is hidden. */
public class TranslateOverlayView extends View {
    private final int frameWidth;
    private final int frameHeight;
    private final List<ScreenText> texts;
    private final List<Item> items = new ArrayList<>();
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int preparedWidth = -1;
    private int preparedHeight = -1;

    public TranslateOverlayView(Context context, int frameWidth, int frameHeight, List<ScreenText> texts) {
        super(context);
        this.frameWidth = Math.max(1, frameWidth);
        this.frameHeight = Math.max(1, frameHeight);
        this.texts = texts;
        density = context.getResources().getDisplayMetrics().density;
        boxPaint.setColor(0xF2202124);
        textPaint.setColor(Color.WHITE);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (preparedWidth != getWidth() || preparedHeight != getHeight()) prepare();
        float radius = 4 * density;
        float padding = 3 * density;
        for (Item item : items) {
            canvas.drawRoundRect(item.area, radius, radius, boxPaint);
            canvas.save();
            canvas.translate(item.area.left + padding,
                    item.area.top + (item.area.height() - item.layout.getHeight()) / 2f);
            item.layout.draw(canvas);
            canvas.restore();
        }
    }

    /** Lays out every translation once the view size is known. */
    @SuppressWarnings("deprecation")
    private void prepare() {
        preparedWidth = getWidth();
        preparedHeight = getHeight();
        items.clear();
        if (preparedWidth == 0 || preparedHeight == 0) return;
        float scaleX = preparedWidth / (float) frameWidth;
        float scaleY = preparedHeight / (float) frameHeight;
        float padding = 3 * density;
        float minSize = 10 * density;
        float maxSize = 32 * density;
        float minWidth = 64 * density;
        for (ScreenText text : texts) {
            RectF box = new RectF(text.getBox().left * scaleX, text.getBox().top * scaleY,
                    text.getBox().right * scaleX, text.getBox().bottom * scaleY);
            float boxWidth = Math.min(preparedWidth, Math.max(box.width(), minWidth) + 2 * padding);
            float left = Math.max(0, Math.min(box.left - padding, preparedWidth - boxWidth));
            int contentWidth = Math.max(1, (int) (boxWidth - 2 * padding));
            float size = Math.max(minSize, Math.min(maxSize, box.height() / text.getLineCount() * 0.75f));
            StaticLayout layout;
            while (true) {
                textPaint.setTextSize(size);
                layout = new StaticLayout(text.getTranslated(), textPaint, contentWidth,
                        Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
                if (layout.getHeight() <= box.height() || size <= minSize) break;
                size = Math.max(minSize, size - density);
            }
            // Text that still does not fit at the smallest size grows downward instead of being cut off.
            float height = Math.max(layout.getHeight(), box.height()) + 2 * padding;
            float top = Math.max(0, Math.min(box.top - padding, preparedHeight - height));
            items.add(new Item(new RectF(left, top, left + boxWidth, top + height), layout));
        }
    }

    private static class Item {
        final RectF area;
        final StaticLayout layout;

        Item(RectF area, StaticLayout layout) {
            this.area = area;
            this.layout = layout;
        }
    }
}
