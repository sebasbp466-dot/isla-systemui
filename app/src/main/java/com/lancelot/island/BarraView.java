package com.lancelot.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Barra de progreso fina. El avance de la musica se actualiza cada 400 ms, no a 60 fps. */
final class BarraView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private int colorRelleno = Color.WHITE;
    private int colorFondo = 0x33FFFFFF;
    private float progreso;

    private ValueAnimator anim;
    private boolean avanzando;
    private float desde;
    private long inicio;
    private long duracionMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!avanzando || !isAttachedToWindow()) return;
            float f = (SystemClock.elapsedRealtime() - inicio) / (float) duracionMs;
            progreso = Math.min(1f, desde + (1f - desde) * f);
            invalidate();
            if (f < 1f) postDelayed(this, 400);
        }
    };

    BarraView(Context c) {
        super(c);
    }

    void setColores(int relleno, int fondo) {
        colorRelleno = relleno;
        colorFondo = fondo;
        invalidate();
    }

    void setProgreso(float f) {
        parar();
        progreso = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    void animarA(float hasta, int ms) {
        parar();
        anim = ValueAnimator.ofFloat(progreso, Math.max(0f, Math.min(1f, hasta)));
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progreso = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    /** Avanza solo desde "desde" hasta el final en msRestantes (cancion en reproduccion). */
    void reproducir(float desde, long msRestantes) {
        parar();
        this.desde = Math.max(0f, Math.min(1f, desde));
        progreso = this.desde;
        invalidate();
        if (msRestantes <= 0L || !isAttachedToWindow()) {
            if (msRestantes > 0L) {
                // todavia no esta en pantalla: arranca al engancharse
                duracionMs = msRestantes;
                inicio = SystemClock.elapsedRealtime();
                avanzando = true;
            }
            return;
        }
        duracionMs = msRestantes;
        inicio = SystemClock.elapsedRealtime();
        avanzando = true;
        postDelayed(tick, 400);
    }

    private void parar() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        avanzando = false;
        removeCallbacks(tick);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (avanzando) postDelayed(tick, 400);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        p.setColor(colorFondo);
        rf.set(0f, 0f, w, h);
        c.drawRoundRect(rf, h / 2f, h / 2f, p);
        float fw = Math.max(h, w * progreso);
        if (progreso > 0f) {
            p.setColor(colorRelleno);
            rf.set(0f, 0f, fw, h);
            c.drawRoundRect(rf, h / 2f, h / 2f, p);
        }
    }
}
