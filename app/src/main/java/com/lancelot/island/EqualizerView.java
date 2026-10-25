package com.lancelot.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Barras de ecualizador. Solo se redibuja (a ~25 fps) mientras suena la musica
 * y la vista esta en pantalla; en pausa queda quieta y no gasta nada.
 */
final class EqualizerView extends View {

    private static final int BARRAS = 4;
    private static final float[] FASE = {0f, 1.7f, 3.1f, 4.6f};
    private static final float[] VEL = {0.0075f, 0.0094f, 0.0063f, 0.0086f};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private boolean animando;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!animando || !isAttachedToWindow()) return;
            invalidate();
            postDelayed(this, 40);
        }
    };

    EqualizerView(Context c) {
        super(c);
        p.setColor(Color.WHITE);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidate();
    }

    void setAnimando(boolean a) {
        if (a == animando) return;
        animando = a;
        removeCallbacks(tick);
        if (a && isAttachedToWindow()) post(tick);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animando) post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float ancho = w / (2f * BARRAS - 1f);
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < BARRAS; i++) {
            float f = animando
                    ? 0.28f + 0.72f * (0.5f + 0.5f * (float) Math.sin(t * VEL[i] * 6.0 + FASE[i]))
                    : 0.30f;
            float alto = h * f;
            float x = i * 2f * ancho;
            rf.set(x, (h - alto) / 2f, x + ancho, (h + alto) / 2f);
            c.drawRoundRect(rf, ancho / 2f, ancho / 2f, p);
        }
    }
}
