package com.example.tank_manager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Line chart of both tank levels (0-100 %) over a time range. A pause of more than
 * HistoryStats.GAP_S between samples (controller off) breaks the lines; samples taken while driving
 * or standing tilted are drawn faint.
 */
public class HistoryChart extends View {

    static final int COLOR_GRAY = Color.parseColor("#6D6D6D");
    static final int COLOR_CLEAN = Color.parseColor("#2E86DE");
    private static final int COLOR_GRID = Color.parseColor("#33000000");
    private static final int COLOR_TEXT = Color.parseColor("#3A3A3A");
    private static final int COLOR_PANEL = Color.parseColor("#FBF7E6");
    private static final int FAINT_ALPHA = 0x50000000;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path faintPath = new Path();
    private final float density;
    private final DashPathEffect dashed;
    private final Date date = new Date();
    private final SimpleDateFormat hourFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("dd.MM", Locale.getDefault());

    private List<HistorySample> samples = new ArrayList<>();
    private long fromTime;
    private long toTime;
    private String emptyText = "";

    public HistoryChart(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        dashed = new DashPathEffect(new float[]{4 * density, 4 * density}, 0);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(density);
        grid.setColor(COLOR_GRID);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        text.setColor(COLOR_TEXT);
        panel.setColor(COLOR_PANEL);
    }

    /** Samples (oldest first) shown between the two Unix times; emptyText is shown without samples. */
    void setData(List<HistorySample> samples, long fromTime, long toTime, String emptyText) {
        this.samples = samples;
        this.fromTime = fromTime;
        this.toTime = toTime;
        this.emptyText = emptyText;
        setContentDescription(emptyText.isEmpty() ? null : emptyText);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(Math.round(220 * density), heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float textH = text.getTextSize();
        float left = text.measureText("100%") + 6 * density;
        float right = w - 8 * density;
        float top = textH + 10 * density;          // room for the legend
        float bottom = h - textH - 8 * density;    // room for the time labels
        canvas.drawRoundRect(0, 0, w, h, 8 * density, 8 * density, panel);

        // Level grid and labels
        text.setTextAlign(Paint.Align.RIGHT);
        for (int level = 0; level <= 100; level += 25) {
            float y = bottom - (bottom - top) * level / 100f;
            grid.setPathEffect(level == 0 || level == 100 ? null : dashed);
            canvas.drawLine(left, y, right, y, grid);
            if (level % 50 == 0) {
                canvas.drawText(level + "%", left - 4 * density, y + textH / 3, text);
            }
        }
        grid.setPathEffect(null);

        // Time labels: hours for a day, dates for longer ranges
        long span = toTime - fromTime;
        if (span <= 0) {
            return;
        }
        SimpleDateFormat format = span <= 2 * 86400 ? hourFormat : dayFormat;
        int ticks = 4;
        for (int i = 0; i <= ticks; i++) {
            long t = fromTime + span * i / ticks;
            float x = left + (right - left) * i / ticks;
            canvas.drawLine(x, top, x, bottom, grid);
            text.setTextAlign(i == 0 ? Paint.Align.LEFT : i == ticks ? Paint.Align.RIGHT : Paint.Align.CENTER);
            date.setTime(t * 1000);
            canvas.drawText(format.format(date), x, h - 6 * density, text);
        }

        // Legend
        text.setTextAlign(Paint.Align.LEFT);
        float lx = left;
        for (int k = 0; k < 2; k++) {
            String name = getContext().getString(k == 0 ? R.string.legend_gray : R.string.legend_clean);
            line.setColor(k == 0 ? COLOR_GRAY : COLOR_CLEAN);
            canvas.drawLine(lx, textH / 2 + 4 * density, lx + 16 * density, textH / 2 + 4 * density, line);
            canvas.drawText(name, lx + 20 * density, textH + 2 * density, text);
            lx += 20 * density + text.measureText(name) + 16 * density;
        }

        if (samples.isEmpty()) {
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emptyText, (left + right) / 2, (top + bottom) / 2, text);
            return;
        }
        // Each series in two paths: steady samples solid, driving or tilted ones faint (levels off)
        for (int k = 0; k < 2; k++) {
            boolean clean = k == 1;
            path.reset();
            faintPath.reset();
            HistorySample previous = null;
            float px = 0;
            float py = 0;
            for (HistorySample s : samples) {
                float x = left + (right - left) * (s.time - fromTime) / (float) span;
                float y = bottom - (bottom - top) * (clean ? s.clean : s.gray) / 100f;
                if (previous == null || s.time - previous.time > HistoryStats.GAP_S) {
                    Path p = s.steady() ? path : faintPath;
                    p.moveTo(x, y);
                    p.lineTo(x, y); // a lone sample still shows as a dot
                } else {
                    Path p = s.steady() && previous.steady() ? path : faintPath;
                    p.moveTo(px, py);
                    p.lineTo(x, y);
                }
                previous = s;
                px = x;
                py = y;
            }
            int color = clean ? COLOR_CLEAN : COLOR_GRAY;
            line.setColor(color);
            canvas.drawPath(path, line);
            line.setColor((color & 0x00FFFFFF) | FAINT_ALPHA);
            canvas.drawPath(faintPath, line);
        }
    }
}
