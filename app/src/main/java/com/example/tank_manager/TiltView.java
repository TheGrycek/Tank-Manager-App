package com.example.tank_manager;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws the camper's attitude in one of three modes: from the side, tilted by the pitch (front
 * up > 0); from behind, tilted by the roll (right side down > 0); or from above on a compass,
 * turned to the heading (clockwise from north). Without a value the camper is drawn grey and level.
 */
public class TiltView extends View {

    public static final int PITCH = 0;
    public static final int ROLL = 1;
    public static final int HEADING = 2;

    // Within these limits (degrees) the camper counts as level / nearly level
    private static final float LEVEL_OK = 1f;
    private static final float LEVEL_NEAR = 3f;
    static final int COLOR_LEVEL = Color.rgb(0, 150, 0);
    static final int COLOR_NEAR = Color.parseColor("#E87823");
    static final int COLOR_TILTED = Color.RED;

    private static final int OUTLINE = Color.parseColor("#1F3A5F");
    private static final int BODY = Color.parseColor("#EAF6FC");
    private static final int WINDOW = Color.parseColor("#8FD3F4");
    private static final int STRIPE = Color.parseColor("#E7C55B");
    private static final int WHEEL = Color.parseColor("#333333");
    private static final int HUB = Color.parseColor("#BBBBBB");
    private static final int TAIL_LIGHT = Color.parseColor("#D63A2F");
    private static final int UNKNOWN = Color.parseColor("#9E9E9E");
    private static final int UNKNOWN_BODY = Color.parseColor("#EEEEEE");
    private static final int UNKNOWN_WINDOW = Color.parseColor("#DDDDDD");

    private final int mode;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final CornerPathEffect rounded = new CornerPathEffect(2f);
    private final DashPathEffect dashed = new DashPathEffect(new float[]{3f, 3f}, 0f);
    private final RectF rect = new RectF();
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);

    // Angle being drawn, and the animation from it to the new value
    private float shown = 0f;
    private float from = 0f;
    private float to = 0f;
    private boolean known = false;

    public TiltView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TiltView);
        try {
            mode = a.getInt(R.styleable.TiltView_tiltMode, PITCH);
        } finally {
            a.recycle();
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        animator.setDuration(400);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            shown = from + (to - from) * (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    /** New angle in degrees, or null when unknown. */
    public void setAngle(@Nullable Float degrees) {
        animator.cancel();
        if (degrees == null) {
            known = false;
            shown = 0f;
            invalidate();
            return;
        }
        from = shown;
        to = degrees;
        if (mode == HEADING && known) {
            // Turn the short way round
            float delta = ((to - from) % 360f + 540f) % 360f - 180f;
            to = from + delta;
        }
        if (!known) {
            from = to;
        }
        known = true;
        animator.start();
    }

    /** Colour telling how level the camper is for this pitch or roll. */
    static int levelColor(float degrees) {
        float a = Math.abs(degrees);
        return a < LEVEL_OK ? COLOR_LEVEL : a < LEVEL_NEAR ? COLOR_NEAR : COLOR_TILTED;
    }

    @Override
    protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Square, as large as the width allows
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = resolveSize(width, heightMeasureSpec);
        int size = Math.min(width, height);
        setMeasuredDimension(width, size);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        // Drawing units: the square is 100 x 100, centred
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(size / 100f, size / 100f);
        stroke.setStrokeWidth(1.6f);
        stroke.setPathEffect(rounded);
        if (mode == HEADING) {
            drawCompass(canvas);
        } else {
            drawLevelLine(canvas);
            canvas.save();
            // Pitch: front (right) up turns anticlockwise; roll: right side down turns clockwise
            canvas.rotate(mode == PITCH ? -shown : shown);
            if (mode == PITCH) {
                drawSide(canvas);
            } else {
                drawRear(canvas);
            }
            canvas.restore();
        }
        canvas.restore();
    }

    private int body() {
        return known ? BODY : UNKNOWN_BODY;
    }

    private int outline() {
        return known ? OUTLINE : UNKNOWN;
    }

    /** Dashed horizontal line: what level looks like. */
    private void drawLevelLine(Canvas canvas) {
        stroke.setPathEffect(dashed);
        stroke.setStrokeWidth(1f);
        stroke.setColor(known ? levelColor(shown) : UNKNOWN);
        canvas.drawLine(-48f, 22f, 48f, 22f, stroke);
        stroke.setPathEffect(rounded);
        stroke.setStrokeWidth(1.6f);
    }

    private void wheel(Canvas canvas, float x, float y, float r) {
        fill.setColor(known ? WHEEL : UNKNOWN);
        canvas.drawCircle(x, y, r, fill);
        fill.setColor(HUB);
        canvas.drawCircle(x, y, r * 0.45f, fill);
    }

    /** Outline of the camper from the side into {@link #path}. */
    private void sideOutline() {
        path.rewind();
        path.moveTo(-42f, -16f);
        path.lineTo(26f, -16f);
        path.lineTo(32f, -13f);
        path.lineTo(32f, -6f);   // alcove over the cab
        path.lineTo(24f, -6f);
        path.lineTo(36f, 3f);    // windscreen
        path.lineTo(42f, 6f);
        path.lineTo(42f, 15f);
        path.lineTo(-42f, 15f);
        path.close();
    }

    /** Alcove camper from the left side, front to the right; wheels stand on y = 22. */
    private void drawSide(Canvas canvas) {
        sideOutline();
        fill.setColor(body());
        canvas.drawPath(path, fill);

        // Decor stripe, windows and door
        fill.setColor(known ? STRIPE : UNKNOWN);
        canvas.drawRect(-42f, 6f, 42f, 9f, fill);
        fill.setColor(known ? WINDOW : UNKNOWN_WINDOW);
        rect.set(-34f, -10f, -18f, -2f);
        canvas.drawRoundRect(rect, 1.5f, 1.5f, fill);
        rect.set(6f, -11f, 14f, -5f);
        canvas.drawRoundRect(rect, 1.5f, 1.5f, fill);
        path.rewind();
        path.moveTo(25f, -3f);
        path.lineTo(32f, 2.5f);
        path.lineTo(25f, 2.5f);
        path.close();
        canvas.drawPath(path, fill);
        stroke.setColor(outline());
        stroke.setStrokeWidth(1f);
        rect.set(-8f, -11f, 1f, 13f);
        canvas.drawRoundRect(rect, 1f, 1f, stroke);

        stroke.setStrokeWidth(1.6f);
        sideOutline();
        canvas.drawPath(path, stroke);

        wheel(canvas, -24f, 16f, 6f);
        wheel(canvas, 26f, 16f, 6f);
    }

    /** Camper from behind; wheels stand on y = 22. */
    private void drawRear(Canvas canvas) {
        rect.set(-28f, -22f, 28f, 14f);
        fill.setColor(body());
        canvas.drawRoundRect(rect, 5f, 5f, fill);

        fill.setColor(known ? STRIPE : UNKNOWN);
        canvas.drawRect(-28f, 4f, 28f, 7f, fill);
        fill.setColor(known ? WINDOW : UNKNOWN_WINDOW);
        rect.set(-15f, -15f, 15f, -4f);
        canvas.drawRoundRect(rect, 2f, 2f, fill);
        fill.setColor(known ? TAIL_LIGHT : UNKNOWN);
        rect.set(-26f, -2f, -22f, 3f);
        canvas.drawRoundRect(rect, 1f, 1f, fill);
        rect.set(22f, -2f, 26f, 3f);
        canvas.drawRoundRect(rect, 1f, 1f, fill);

        stroke.setColor(outline());
        rect.set(-28f, -22f, 28f, 14f);
        canvas.drawRoundRect(rect, 5f, 5f, stroke);
        // Bumper
        canvas.drawLine(-29f, 12f, 29f, 12f, stroke);

        fill.setColor(known ? WHEEL : UNKNOWN);
        rect.set(-25f, 13f, -14f, 22f);
        canvas.drawRoundRect(rect, 2f, 2f, fill);
        rect.set(14f, 13f, 25f, 22f);
        canvas.drawRoundRect(rect, 2f, 2f, fill);
    }

    /** Compass rose with north up and the camper from above, its front turned to the heading. */
    private void drawCompass(Canvas canvas) {
        stroke.setColor(outline());
        stroke.setStrokeWidth(1.6f);
        canvas.drawCircle(0f, 0f, 44f, stroke);
        stroke.setStrokeWidth(1f);
        for (int deg = 0; deg < 360; deg += 30) {
            if (deg % 90 == 0) continue;
            canvas.save();
            canvas.rotate(deg);
            canvas.drawLine(0f, -44f, 0f, -40f, stroke);
            canvas.restore();
        }
        text.setTextSize(12f);
        String[] names = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90);
            float x = (float) (Math.sin(a) * 36);
            float y = (float) (-Math.cos(a) * 36) + 4f;
            text.setColor(i == 0 ? (known ? Color.RED : UNKNOWN) : outline());
            canvas.drawText(names[i], x, y, text);
        }

        canvas.save();
        canvas.rotate(shown);
        // Camper from above, front up
        rect.set(-10f, -24f, 10f, 24f);
        fill.setColor(body());
        canvas.drawRoundRect(rect, 4f, 4f, fill);
        fill.setColor(known ? WINDOW : UNKNOWN_WINDOW);
        rect.set(-8f, -22f, 8f, -16f);
        canvas.drawRoundRect(rect, 2f, 2f, fill);
        fill.setColor(known ? STRIPE : UNKNOWN);
        rect.set(-6f, -8f, 6f, 4f);
        canvas.drawRoundRect(rect, 1.5f, 1.5f, fill);
        stroke.setColor(outline());
        stroke.setStrokeWidth(1.6f);
        rect.set(-10f, -24f, 10f, 24f);
        canvas.drawRoundRect(rect, 4f, 4f, stroke);
        // Arrow ahead of the front
        path.rewind();
        path.moveTo(0f, -33f);
        path.lineTo(-4f, -27f);
        path.lineTo(4f, -27f);
        path.close();
        fill.setColor(outline());
        canvas.drawPath(path, fill);
        canvas.restore();
    }
}
