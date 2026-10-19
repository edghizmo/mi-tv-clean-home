package com.cleanhome.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** FrameLayout that is always 16:9 (TV banner shape), sized from its width. */
public class AspectFrame extends FrameLayout {
    public AspectFrame(Context c, AttributeSet a) {
        super(c, a);
        setClipToOutline(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(w * 9f / 16f);
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
