package com.emmanuel.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The cinematic shelf's backdrop: an app's artwork reduced to 48×27 pixels, saturated and smoothed,
 * then stretched over the screen by its ImageView. Bilinear filtering of an image that small is a
 * blur that costs one tiny texture; a real blur would mean RenderScript or a shader pass per frame,
 * which a 1 GB TV cannot spare.
 */
final class HeroArt {
    static final int W = 48, H = 27;
    private static final float SATURATION = 1.7f;
    private static final int[] PX = new int[W * H];
    private static final int[] TEMP = new int[W * H];

    private HeroArt() {
    }

    /** The softened artwork, about 5 KB; null when there is nothing to draw. */
    static Bitmap from(Drawable live) {
        if (live == null) return null;
        // Draw a private copy: changing the bounds of the drawable a tile shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(c);
        d.setBounds(saved);
        // Halve twice: each filtered halving averages 2×2 pixels, where one 4× step would skip most of them.
        Bitmap half = Bitmap.createScaledBitmap(big, W * 2, H * 2, true);
        big.recycle();
        Bitmap small = Bitmap.createScaledBitmap(half, W, H, true);
        half.recycle();

        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        synchronized (PX) {
            out.getPixels(PX, 0, W, 0, 0, W, H);
            saturate(PX);
            // Three box passes approximate a gaussian, so the stretched image shows no seams between pixels.
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(PX, TEMP, W, H, 2, true);
                boxBlur(TEMP, PX, W, H, 2, false);
            }
            out.setPixels(PX, 0, W, 0, 0, W, H);
        }
        return out;
    }

    private static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(clamp(lum + (r - lum) * SATURATION), clamp(lum + (g - lum) * SATURATION),
                    clamp(lum + (b - lum) * SATURATION));
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** Sliding-window box blur along rows or columns, edges clamped. Writes into dst without allocations. */
    private static void boxBlur(int[] src, int[] dst, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        int stride = horizontal ? 1 : w;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int j = k < 0 ? 0 : (k >= length ? length - 1 : k);
                int p = src[base + j * stride];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            dst[base] = 0xFF000000 | ((r / span) << 16) | ((g / span) << 8) | (b / span);
            for (int i = 1; i < length; i++) {
                int addIdx = Math.min(length - 1, i + radius);
                int subIdx = Math.max(0, i - radius - 1);
                int addP = src[base + addIdx * stride];
                int subP = src[base + subIdx * stride];
                r += ((addP >> 16) & 0xFF) - ((subP >> 16) & 0xFF);
                g += ((addP >> 8) & 0xFF) - ((subP >> 8) & 0xFF);
                b += (addP & 0xFF) - (subP & 0xFF);
                dst[base + i * stride] = 0xFF000000 | ((r / span) << 16) | ((g / span) << 8) | (b / span);
            }
        }
    }
}
