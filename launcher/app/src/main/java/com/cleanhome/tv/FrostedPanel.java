package com.cleanhome.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * Translucent glass panel matching the top-bar pills exactly (see
 * res/drawable/pill_bg.xml): white at 12% opacity with a 1dp white outline at
 * 14%. Used for the dock band and the Now Playing card.
 */
public class FrostedPanel extends LinearLayout {
    private static final int FILL = 0x1FFFFFFF;    // = pill_bg solid
    private static final int STROKE = 0x24FFFFFF;  // = pill_bg stroke

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float radius;

    public FrostedPanel(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
        float dp = getResources().getDisplayMetrics().density;
        radius = 30 * dp;
        fill.setColor(FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp);
        stroke.setColor(STROKE);
    }

    public void setCornerRadius(float r) {
        radius = r;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = stroke.getStrokeWidth() / 2f;
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawRoundRect(bounds, radius, radius, fill);
        canvas.drawRoundRect(bounds, radius, radius, stroke);
    }
}
