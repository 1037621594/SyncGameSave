package com.example.syncgamesave.pokemon;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out left to right and wraps to a new row when the width is used up. */
public class FlowLayout extends ViewGroup {
    private final int gap;

    public FlowLayout(Context context, int gap) {
        super(context);
        this.gap = gap;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int available = width - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int childWidth = child.getMeasuredWidth();
            if (x > 0 && x + childWidth > available) {
                y += rowHeight + gap;
                x = 0;
                rowHeight = 0;
            }
            x += childWidth + gap;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        setMeasuredDimension(width, y + rowHeight + getPaddingTop() + getPaddingBottom());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = right - left - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (x > 0 && x + childWidth > available) {
                y += rowHeight + gap;
                x = 0;
                rowHeight = 0;
            }
            int childLeft = getPaddingLeft() + x;
            int childTop = getPaddingTop() + y;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
            x += childWidth + gap;
            rowHeight = Math.max(rowHeight, childHeight);
        }
    }
}
