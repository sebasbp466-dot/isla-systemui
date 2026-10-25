package com.lancelot.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Fondo del tema Liquid Glass: manchas de luz de colores que derivan despacio. */
final class AuroraView extends View {

    private static final int[] COLORES = {0xFFFF4DA6, 0xFF00E5FF, 0xFF7C4DFF, 0xFFFFB347};
    private static final float[] CX = {0.15f, 0.85f, 0.30f, 0.80f};
    private static final float[] CY = {0.15f, 0.38f, 0.72f, 0.95f};
    private static final float[] FASE = {0f, 1.6f, 3.1f, 4.7f};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient[] g;
    private ValueAnimator anim;
    private float t;
    private int lastW;
    private int lastH;

    AuroraView(Context c) {
        super(c);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
        anim.setDuration(26000);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                t = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (g == null || w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            float r = Math.max(w, h) * 0.55f;
            g = new RadialGradient[COLORES.length];
            for (int i = 0; i < g.length; i++) {
                int col = COLORES[i] & 0x00FFFFFF;
                g[i] = new RadialGradient(0f, 0f, r,
                        new int[]{0x66000000 | col, 0x00000000 | col}, null,
                        Shader.TileMode.CLAMP);
            }
        }
        for (int i = 0; i < g.length; i++) {
            float x = w * CX[i] + (float) Math.sin(t + FASE[i]) * w * 0.14f;
            float y = h * CY[i] + (float) Math.cos(t * 1.3f + FASE[i]) * h * 0.07f;
            c.save();
            c.translate(x, y);
            p.setShader(g[i]);
            float r = Math.max(w, h) * 0.55f;
            c.drawRect(-r, -r, r, r, p);
            c.restore();
        }
    }
}
