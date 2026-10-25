package com.lancelot.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Aro de progreso pequeño para la capsula compacta (descargas). */
final class AnilloView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private int color = Color.WHITE;
    private float progreso;

    AnilloView(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(int color, float progreso) {
        this.color = color;
        this.progreso = Math.max(0f, Math.min(1f, progreso));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float lado = Math.min(getWidth(), getHeight());
        if (lado <= 0f) return;
        float ancho = lado * 0.14f;
        p.setStrokeWidth(ancho);
        float m = ancho / 2f;
        float x = (getWidth() - lado) / 2f;
        float y = (getHeight() - lado) / 2f;
        rf.set(x + m, y + m, x + lado - m, y + lado - m);
        p.setColor((color & 0x00FFFFFF) | 0x40000000);
        c.drawArc(rf, 0f, 360f, false, p);
        p.setColor(color);
        c.drawArc(rf, -90f, 360f * progreso, false, p);
    }
}
