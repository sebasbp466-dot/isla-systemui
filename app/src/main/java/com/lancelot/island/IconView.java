package com.lancelot.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Iconos del motor Xiaomi/iPhone dibujados a mano en una caja de 100x100:
 * cronometro con la aguja girando, linterna (con rayos o con halo), cast, bandera, pausa...
 */
final class IconView extends View {

    static final int CRONO = 1;
    static final int LINTERNA = 2;
    static final int LINTERNA_RAYOS = 3;
    static final int CAST = 4;
    static final int BANDERA = 5;
    static final int PAUSA = 6;
    static final int PLAY = 7;
    static final int SIGUIENTE = 8;
    static final int ANTERIOR = 9;
    static final int REINICIAR = 10;
    static final int MAS = 11;
    static final int PARAR = 12;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private int tipo;
    private int color = Color.WHITE;
    private float alfa = 1f;
    private float anguloFijo = -1f;
    private boolean girando;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!girando || !isAttachedToWindow()) return;
            invalidate();
            postDelayed(this, 100);
        }
    };

    IconView(Context c) {
        super(c);
    }

    void set(int tipo, int color) {
        this.tipo = tipo;
        this.color = color;
        invalidate();
    }

    void setAlfa(float a) {
        alfa = a;
        invalidate();
    }

    /** Cronometro: la aguja da una vuelta por minuto (segundero) mientras gira. */
    void setGirando(boolean g) {
        girando = g;
        removeCallbacks(tick);
        if (g && isAttachedToWindow()) post(tick);
        invalidate();
    }

    void setAngulo(float grados) {
        anguloFijo = grados;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (girando) post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private static int conAlfa(int c, float a) {
        int al = Math.round(Color.alpha(c) * Math.max(0f, Math.min(1f, a)));
        return (c & 0x00FFFFFF) | (al << 24);
    }

    private static int oscuro(int c) {
        int r = (int) (Color.red(c) * 0.22f);
        int g = (int) (Color.green(c) * 0.22f);
        int b = (int) (Color.blue(c) * 0.30f);
        return Color.rgb(r, g, b);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h) / 100f;
        c.save();
        c.translate((w - 100f * s) / 2f, (h - 100f * s) / 2f);
        c.scale(s, s);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        switch (tipo) {
            case CRONO:
                crono(c);
                break;
            case LINTERNA:
                linterna(c, false);
                break;
            case LINTERNA_RAYOS:
                linterna(c, true);
                break;
            case CAST:
                cast(c);
                break;
            case BANDERA:
                bandera(c);
                break;
            case PAUSA:
                pausa(c);
                break;
            case PLAY:
                play(c);
                break;
            case SIGUIENTE:
                siguiente(c, false);
                break;
            case ANTERIOR:
                siguiente(c, true);
                break;
            case REINICIAR:
                reiniciar(c);
                break;
            case MAS:
                mas(c);
                break;
            case PARAR:
                rf.set(26f, 26f, 74f, 74f);
                p.setColor(conAlfa(color, alfa));
                c.drawRoundRect(rf, 9f, 9f, p);
                break;
            default:
                break;
        }
        c.restore();
    }

    private void crono(Canvas c) {
        p.setColor(conAlfa(color, alfa));
        c.drawCircle(50f, 58f, 37f, p);
        rf.set(37f, 3f, 63f, 12f);
        c.drawRoundRect(rf, 4f, 4f, p);
        float ang;
        if (anguloFijo >= 0f) {
            ang = anguloFijo;
        } else {
            ang = (SystemClock.elapsedRealtime() % 60000L) / 60000f * 360f;
        }
        double a = Math.toRadians(ang);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8f);
        p.setColor(conAlfa(oscuro(color), alfa));
        c.drawLine(50f, 58f, 50f + (float) Math.sin(a) * 23f, 58f - (float) Math.cos(a) * 23f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void linterna(Canvas c, boolean rayos) {
        float dy = rayos ? 8f : 0f;
        if (!rayos) {
            // halo de luz sobre la cabeza
            p.setShader(new RadialGradient(50f, 26f, 46f,
                    new int[]{conAlfa(Color.WHITE, 0.62f * alfa), conAlfa(Color.WHITE, 0.18f * alfa),
                            0x00FFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(50f, 26f, 46f, p);
            p.setShader(null);
        }
        p.setColor(conAlfa(color, 0.94f * alfa));
        rf.set(24f, 22f + dy, 76f, 33f + dy);
        c.drawRoundRect(rf, 4f, 4f, p);
        path.reset();
        path.moveTo(30f, 33f + dy);
        path.lineTo(70f, 33f + dy);
        path.lineTo(61f, 50f + dy);
        path.lineTo(39f, 50f + dy);
        path.close();
        c.drawPath(path, p);
        rf.set(38f, 50f + dy, 62f, 94f);
        c.drawRoundRect(rf, 6f, 6f, p);
        p.setColor(conAlfa(0xFF2A2F36, alfa));
        rf.set(46f, 62f + dy, 54f, 77f + dy);
        c.drawRoundRect(rf, 4f, 4f, p);
        if (rayos) {
            p.setColor(conAlfa(color, 0.9f * alfa));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5f);
            for (int i = 0; i < 3; i++) {
                float x = 38f + i * 12f;
                c.drawLine(x, 3f, x, 14f, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }

    private void cast(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6f);
        p.setColor(conAlfa(color, 0.7f * alfa));
        path.reset();
        path.moveTo(62f, 78f);
        path.lineTo(76f, 78f);
        path.quadTo(88f, 78f, 88f, 66f);
        path.lineTo(88f, 32f);
        path.quadTo(88f, 20f, 76f, 20f);
        path.lineTo(24f, 20f);
        path.quadTo(12f, 20f, 12f, 32f);
        path.lineTo(12f, 34f);
        c.drawPath(path, p);
        for (int r = 14; r <= 42; r += 14) {
            rf.set(12f - r, 78f - r, 12f + r, 78f + r);
            c.drawArc(rf, 270f, 90f, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(12f, 78f, 4.5f, p);
    }

    private void bandera(Canvas c) {
        p.setColor(conAlfa(color, alfa));
        rf.set(28f, 16f, 37f, 84f);
        c.drawRoundRect(rf, 4f, 4f, p);
        path.reset();
        path.moveTo(37f, 18f);
        path.lineTo(80f, 42f);
        path.lineTo(37f, 62f);
        path.close();
        c.drawPath(path, p);
    }

    private void pausa(Canvas c) {
        p.setColor(conAlfa(color, alfa));
        rf.set(28f, 22f, 43f, 78f);
        c.drawRoundRect(rf, 5f, 5f, p);
        rf.set(57f, 22f, 72f, 78f);
        c.drawRoundRect(rf, 5f, 5f, p);
    }

    private void play(Canvas c) {
        p.setColor(conAlfa(color, alfa));
        path.reset();
        path.moveTo(32f, 20f);
        path.lineTo(32f, 80f);
        path.lineTo(80f, 50f);
        path.close();
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeWidth(6f);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void siguiente(Canvas c, boolean atras) {
        p.setColor(conAlfa(color, alfa));
        c.save();
        if (atras) c.scale(-1f, 1f, 50f, 50f);
        path.reset();
        path.moveTo(22f, 22f);
        path.lineTo(22f, 78f);
        path.lineTo(64f, 50f);
        path.close();
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeWidth(5f);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        rf.set(68f, 22f, 79f, 78f);
        c.drawRoundRect(rf, 4f, 4f, p);
        c.restore();
    }

    private void reiniciar(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8f);
        p.setColor(conAlfa(color, alfa));
        rf.set(20f, 20f, 80f, 80f);
        c.drawArc(rf, -50f, 290f, false, p);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(66f, 6f);
        path.lineTo(86f, 28f);
        path.lineTo(58f, 32f);
        path.close();
        c.drawPath(path, p);
    }

    private void mas(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(9f);
        p.setColor(conAlfa(color, alfa));
        c.drawLine(50f, 24f, 50f, 76f, p);
        c.drawLine(24f, 50f, 76f, 50f, p);
        p.setStyle(Paint.Style.FILL);
    }
}
