package com.lancelot.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Barra de progreso de la tarjeta de musica: se redibuja sola mientras suena. */
final class BarraMusica extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private Evento ev;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            invalidate();
            if (ev != null && ev.reproduciendo) postDelayed(this, 80);
        }
    };

    BarraMusica(Context c) {
        super(c);
    }

    void set(Evento e) {
        ev = e;
        removeCallbacks(tick);
        if (isAttachedToWindow()) post(tick);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent m) {
        if (ev == null || ev.duracion <= 0) return false;
        int a = m.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (a == MotionEvent.ACTION_UP) {
            float f = Math.max(0f, Math.min(1f, m.getX() / Math.max(1, getWidth())));
            ev.buscar((long) (f * ev.duracion));
            invalidate();
            return true;
        }
        return a == MotionEvent.ACTION_MOVE;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float alto = Math.min(h, Math.max(4f, h * 0.5f));
        float y = (h - alto) / 2f;
        p.setColor(0x38FFFFFF);
        rf.set(0, y, w, y + alto);
        c.drawRoundRect(rf, alto / 2f, alto / 2f, p);
        float f = 0f;
        if (ev != null && ev.duracion > 0) {
            f = Math.max(0f, Math.min(1f, ev.posicionAhora() / (float) ev.duracion));
        }
        p.setColor(0xFFFFFFFF);
        rf.set(0, y, Math.max(alto, w * f), y + alto);
        c.drawRoundRect(rf, alto / 2f, alto / 2f, p);
    }
}
