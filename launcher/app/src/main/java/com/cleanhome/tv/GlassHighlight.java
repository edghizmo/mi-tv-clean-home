package com.cleanhome.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The single translucent "glass" focus highlight. It follows whatever view the
 * current {@link Target} resolves to. It is drawn at the target's live
 * position plus an offset, and only the offset springs to zero: so it rides
 * rigidly with the target while the list scrolls or the target scales, and
 * the spring only covers the jump from the old target to the new one. It
 * only draws frames while moving, so it costs nothing at rest.
 */
public class GlassHighlight extends View {

    /** Resolves the focused view lazily; it may not be laid out yet after a scroll. */
    public interface Target {
        View view();
    }

    private static final float STIFFNESS = 340f;
    private static final float DAMPING = 2f * 0.74f * (float) Math.sqrt(STIFFNESS);

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final LinearGradient sheen;
    private final RectF rect = new RectF();
    private final int[] selfLoc = new int[2];
    private final int[] loc = new int[2];

    private final float[] pos = new float[4];   // drawn rect: goal + off
    private final float[] off = new float[4];   // springs to zero
    private final float[] vel = new float[4];   // of off
    private final float[] goal = new float[4];
    private float radius, goalRadius;
    private boolean placed, retarget;
    private Target target;
    private long lastFrameNanos;
    private long keepAliveUntil;

    public GlassHighlight(Context c, AttributeSet a) {
        super(c, a);
        float dp = getResources().getDisplayMetrics().density;
        // Gradient defined over a unit height and scaled to the rect each frame.
        sheen = new LinearGradient(0, 0, 0, 1,
                new int[]{0x40FFFFFF, 0x1AFFFFFF, 0x0DFFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        fill.setShader(sheen);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(0x66FFFFFF);
        setWillNotDraw(false);
    }

    /** Move to a new target. animate=false jumps there (first show, restore). */
    public void setTarget(Target t, float cornerRadius, boolean animate) {
        target = t;
        goalRadius = cornerRadius;
        if (!animate) placed = false;
        else retarget = true;
        poke();
    }

    public void hide() {
        target = null;
        placed = false;
        invalidate();
    }

    /** Keep tracking for a moment, e.g. while a scroll or scale animation runs. */
    public void poke() {
        keepAliveUntil = SystemClock.uptimeMillis() + 700;
        lastFrameNanos = 0;
        postInvalidateOnAnimation();
    }

    private boolean resolveGoal() {
        View v = target == null ? null : target.view();
        if (v == null || !v.isAttachedToWindow() || v.getWidth() == 0) return false;
        getLocationInWindow(selfLoc);
        v.getLocationInWindow(loc);
        float l = loc[0] - selfLoc[0];
        float t = loc[1] - selfLoc[1];
        goal[0] = l;
        goal[1] = t;
        goal[2] = l + v.getWidth() * v.getScaleX();
        goal[3] = t + v.getHeight() * v.getScaleY();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean haveGoal = resolveGoal();
        boolean alive = SystemClock.uptimeMillis() < keepAliveUntil;
        if (!haveGoal) {
            if (target != null && alive) postInvalidateOnAnimation();
            if (!placed) return;
        } else if (!placed) {
            System.arraycopy(goal, 0, pos, 0, 4);
            for (int i = 0; i < 4; i++) off[i] = vel[i] = 0;
            radius = goalRadius;
            placed = true;
            retarget = false;
        } else {
            // New target: start from where the highlight is drawn now, keeping
            // its velocity, so a quick second press continues the motion.
            if (retarget) {
                for (int i = 0; i < 4; i++) off[i] = pos[i] - goal[i];
                retarget = false;
            }
            long now = System.nanoTime();
            float dt = lastFrameNanos == 0 ? 1f / 60f : Math.min((now - lastFrameNanos) / 1e9f, 1f / 30f);
            lastFrameNanos = now;
            // Two semi-implicit Euler substeps per frame keep the spring stable.
            float h = dt / 2f;
            for (int s = 0; s < 2; s++) {
                for (int i = 0; i < 4; i++) {
                    float a = -STIFFNESS * off[i] - DAMPING * vel[i];
                    vel[i] += a * h;
                    off[i] += vel[i] * h;
                }
            }
            for (int i = 0; i < 4; i++) pos[i] = goal[i] + off[i];
            radius += (goalRadius - radius) * Math.min(1f, dt * 14f);
        }

        if (target == null) return;
        rect.set(pos[0], pos[1], pos[2], pos[3]);
        shaderMatrix.setScale(1f, Math.max(1f, rect.height()));
        shaderMatrix.postTranslate(0, rect.top);
        sheen.setLocalMatrix(shaderMatrix);
        canvas.drawRoundRect(rect, radius, radius, fill);
        canvas.drawRoundRect(rect, radius, radius, stroke);

        boolean settled = true;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(off[i]) > 0.3f || Math.abs(vel[i]) > 0.3f) settled = false;
        }
        if (!settled || alive || Math.abs(radius - goalRadius) > 0.3f) postInvalidateOnAnimation();
    }
}
