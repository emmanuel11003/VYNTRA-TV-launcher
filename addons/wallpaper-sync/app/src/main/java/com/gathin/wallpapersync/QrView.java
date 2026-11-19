package com.gathin.wallpapersync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.util.Objects;

/** Renders a high-contrast QR code scaled to whole pixels per module. */
final class QrView extends View {
    private static final int QUIET = 4;
    private static final int LIGHT = 0xFFEAF0F6;
    private static final int DARK = 0xFF0E1726;

    private final Paint paint = new Paint();
    private final Path qrPath = new Path();
    private boolean[][] modules;
    private String lastText;

    QrView(Context context) {
        super(context);
        paint.setColor(DARK);
        paint.setAntiAlias(false);
        float radius = 12 * context.getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    boolean setText(String text) {
        if (Objects.equals(text, lastText) && modules != null) {
            return true;
        }
        lastText = text;
        modules = Qr.encode(text);
        rebuildPath();
        invalidate();
        return modules != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildPath();
    }

    private void rebuildPath() {
        qrPath.reset();
        int w = getWidth(), h = getHeight();
        if (modules == null || w <= 0 || h <= 0) return;
        int n = modules.length + 2 * QUIET;
        int side = Math.min(w, h);
        int scale = Math.max(1, side / n);
        int left = (w - scale * n) / 2 + QUIET * scale;
        int top = (h - scale * n) / 2 + QUIET * scale;
        for (int y = 0; y < modules.length; y++) {
            int startX = -1;
            for (int x = 0; x <= modules.length; x++) {
                boolean on = x < modules.length && modules[y][x];
                if (on && startX < 0) {
                    startX = x;
                } else if (!on && startX >= 0) {
                    qrPath.addRect(left + startX * scale, top + y * scale,
                            left + x * scale, top + (y + 1) * scale, Path.Direction.CW);
                    startX = -1;
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(LIGHT);
        if (!qrPath.isEmpty()) {
            canvas.drawPath(qrPath, paint);
        }
    }
}
