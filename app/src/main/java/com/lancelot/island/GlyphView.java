package com.lancelot.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * Iconos vectoriales dibujados a mano con animacion de entrada y de pulso.
 * Todo se dibuja en una caja de 24x24 que se escala al tamaño de la vista.
 */
final class GlyphView extends View {

    static final int NADA = 0;
    static final int LINTERNA = 1;
    static final int BATERIA = 2;
    static final int CAMPANA = 3;
    static final int AURICULARES = 4;
    static final int NOTA = 5;
    static final int CHECK = 6;
    static final int PLAY = 7;
    static final int PAUSA = 8;
    static final int SIGUIENTE = 9;
    static final int ANTERIOR = 10;
    static final int RAYO = 11;

    private static final int AMARILLO = 0xFFFFD60A;
    private static final int VERDE = 0xFF34C759;
    private static final int ROJO = 0xFFFF453A;
    private static final int NARANJA = 0xFFFF9F0A;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path parcial = new Path();
    private final RectF rf = new RectF();

    private int tipo = NADA;
    private float nivel;
    private boolean activo;
    private int modo;
    private int color = Color.WHITE;
    /** Entrada: 0..1 una sola vez. */
    private float anim = 1f;
    /** Pulso: 0..1 en bucle mientras es visible. */
    private float pulso;

    private ValueAnimator animEntrada;
    private ValueAnimator animPulso;

    GlyphView(Context c) {
        super(c);
    }

    /** nivel: 0..1. modo: para la campana (0 silencio, 1 vibracion, 2 timbre). */
    void set(int tipo, float nivel, boolean activo, int modo, int color) {
        detener();
        this.tipo = tipo;
        this.nivel = Math.max(0f, Math.min(1f, nivel));
        this.activo = activo;
        this.modo = modo;
        this.color = color;
        invalidate();
    }

    /** Lanza la animacion de entrada (y el pulso si el icono lo usa). */
    void entrada() {
        detener();
        anim = 0f;
        animEntrada = ValueAnimator.ofFloat(0f, 1f);
        animEntrada.setDuration(750);
        animEntrada.setInterpolator(new DecelerateInterpolator());
        animEntrada.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                anim = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animEntrada.start();

        boolean pulsa = activo && (tipo == LINTERNA || tipo == BATERIA || tipo == RAYO);
        if (pulsa) {
            animPulso = ValueAnimator.ofFloat(0f, 1f);
            animPulso.setDuration(1100);
            animPulso.setRepeatCount(ValueAnimator.INFINITE);
            animPulso.setInterpolator(new LinearInterpolator());
            animPulso.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pulso = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            animPulso.start();
        }
    }

    void detener() {
        if (animEntrada != null) {
            animEntrada.cancel();
            animEntrada = null;
        }
        if (animPulso != null) {
            animPulso.cancel();
            animPulso = null;
        }
        anim = 1f;
        pulso = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        detener();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        if (s <= 0f) return;
        c.save();
        c.translate((getWidth() - 24f * s) / 2f, (getHeight() - 24f * s) / 2f);
        c.scale(s, s);
        switch (tipo) {
            case LINTERNA:
                linterna(c);
                break;
            case BATERIA:
                bateria(c);
                break;
            case RAYO:
                rayo(c);
                break;
            case CAMPANA:
                campana(c);
                break;
            case AURICULARES:
                auriculares(c);
                break;
            case NOTA:
                nota(c);
                break;
            case CHECK:
                check(c);
                break;
            case PLAY:
                relleno();
                path.reset();
                path.moveTo(8f, 5f);
                path.lineTo(19f, 12f);
                path.lineTo(8f, 19f);
                path.close();
                c.drawPath(path, p);
                break;
            case PAUSA:
                relleno();
                rf.set(7f, 5.5f, 10.5f, 18.5f);
                c.drawRoundRect(rf, 1f, 1f, p);
                rf.set(13.5f, 5.5f, 17f, 18.5f);
                c.drawRoundRect(rf, 1f, 1f, p);
                break;
            case SIGUIENTE:
                relleno();
                path.reset();
                path.moveTo(6f, 6f);
                path.lineTo(14.5f, 12f);
                path.lineTo(6f, 18f);
                path.close();
                c.drawPath(path, p);
                rf.set(16f, 6f, 18.5f, 18f);
                c.drawRoundRect(rf, 1f, 1f, p);
                break;
            case ANTERIOR:
                relleno();
                path.reset();
                path.moveTo(18f, 6f);
                path.lineTo(9.5f, 12f);
                path.lineTo(18f, 18f);
                path.close();
                c.drawPath(path, p);
                rf.set(5.5f, 6f, 8f, 18f);
                c.drawRoundRect(rf, 1f, 1f, p);
                break;
            default:
                break;
        }
        c.restore();
    }

    private void relleno() {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
    }

    private void trazo(float ancho) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(ancho);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    private static int conAlfa(int c, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (c & 0x00FFFFFF) | (al << 24);
    }

    private float seno() {
        return (float) Math.sin(pulso * 2.0 * Math.PI);
    }

    private void linterna(Canvas c) {
        // Encendida: el haz crece con la entrada. Apagada: se encoge.
        float haz = activo ? anim : 1f - anim;
        float f = haz * (0.88f + 0.12f * seno());
        int cuerpo = activo ? AMARILLO : 0xFFC7C7CC;

        if (haz > 0.02f) {
            relleno();
            p.setColor(conAlfa(AMARILLO, 0.22f * haz));
            c.drawCircle(12f, 7.5f, 7.5f * haz, p);
        }

        relleno();
        p.setColor(cuerpo);
        path.reset();
        path.moveTo(8.2f, 7f);
        path.lineTo(15.8f, 7f);
        path.lineTo(14.6f, 11f);
        path.lineTo(9.4f, 11f);
        path.close();
        c.drawPath(path, p);
        rf.set(9.4f, 11f, 14.6f, 21.5f);
        c.drawRoundRect(rf, 1.6f, 1.6f, p);
        p.setColor(0x66000000);
        rf.set(11.2f, 13.5f, 12.8f, 17f);
        c.drawRoundRect(rf, 0.8f, 0.8f, p);

        if (haz > 0.02f) {
            trazo(1.6f);
            p.setColor(conAlfa(AMARILLO, haz));
            c.drawLine(12f, 5.2f, 12f, 5.2f - 3.6f * f, p);
            c.drawLine(8.4f, 5.8f, 8.4f - 2.6f * f, 5.8f - 2.6f * f, p);
            c.drawLine(15.6f, 5.8f, 15.6f + 2.6f * f, 5.8f - 2.6f * f, p);
        }
    }

    private void bateria(Canvas c) {
        float lleno = nivel * anim;
        int col;
        if (nivel <= 0.15f) {
            col = ROJO;
        } else if (nivel <= 0.30f && !activo) {
            col = NARANJA;
        } else {
            col = activo ? VERDE : Color.WHITE;
        }

        trazo(1.5f);
        p.setColor(conAlfa(Color.WHITE, 0.9f));
        rf.set(2.5f, 6.5f, 19.5f, 17.5f);
        c.drawRoundRect(rf, 2.6f, 2.6f, p);
        relleno();
        p.setColor(conAlfa(Color.WHITE, 0.9f));
        rf.set(20.3f, 10f, 22f, 14f);
        c.drawRoundRect(rf, 0.8f, 0.8f, p);

        float ancho = 13.6f * lleno;
        if (ancho > 0.8f) {
            relleno();
            p.setColor(col);
            rf.set(4.2f, 8.2f, 4.2f + ancho, 15.8f);
            c.drawRoundRect(rf, 1.3f, 1.3f, p);
        }

        if (activo) {
            relleno();
            p.setColor(conAlfa(Color.WHITE, 0.66f + 0.34f * (0.5f + 0.5f * seno())));
            path.reset();
            path.moveTo(12.9f, 8.0f);
            path.lineTo(9.3f, 12.3f);
            path.lineTo(11.7f, 12.3f);
            path.lineTo(10.9f, 16.0f);
            path.lineTo(14.7f, 11.4f);
            path.lineTo(12.2f, 11.4f);
            path.close();
            c.drawPath(path, p);
        }
    }

    private void rayo(Canvas c) {
        float esc = 1f + 0.08f * seno();
        c.save();
        c.scale(esc, esc, 12f, 12f);
        relleno();
        p.setColor(color);
        path.reset();
        path.moveTo(13.5f, 2.5f);
        path.lineTo(6f, 13.2f);
        path.lineTo(11f, 13.2f);
        path.lineTo(10f, 21.5f);
        path.lineTo(18f, 10.3f);
        path.lineTo(12.8f, 10.3f);
        path.close();
        c.drawPath(path, p);
        c.restore();
    }

    private void campana(Canvas c) {
        float intensidad = modo == 2 ? 1f : (modo == 1 ? 0.6f : 0.35f);
        float ang = intensidad * 16f * (float) Math.sin(anim * Math.PI * 7.0) * (1f - anim);
        c.save();
        c.rotate(ang, 12f, 4.5f);
        relleno();
        p.setColor(modo == 0 ? 0xFFC7C7CC : color);
        path.reset();
        path.moveTo(5.5f, 17.5f);
        path.lineTo(6.8f, 16f);
        path.cubicTo(6.8f, 16f, 7f, 13f, 7f, 11f);
        path.cubicTo(7f, 8f, 9f, 6.2f, 12f, 6.2f);
        path.cubicTo(15f, 6.2f, 17f, 8f, 17f, 11f);
        path.cubicTo(17f, 13f, 17.2f, 16f, 17.2f, 16f);
        path.lineTo(18.5f, 17.5f);
        path.close();
        c.drawPath(path, p);
        c.drawCircle(12f, 5.2f, 1f, p);
        c.drawCircle(12f, 19.4f, 1.7f, p);
        c.restore();

        if (modo == 1) {
            trazo(1.6f);
            p.setColor(conAlfa(color, 0.8f));
            c.drawLine(2.6f, 9f, 2.6f, 15f, p);
            c.drawLine(21.4f, 9f, 21.4f, 15f, p);
        } else if (modo == 0) {
            trazo(2f);
            p.setColor(ROJO);
            c.drawLine(4.5f, 4.5f, 19.5f, 19.5f, p);
        }
    }

    private void auriculares(Canvas c) {
        float esc = 1f + 0.12f * (float) Math.sin(anim * Math.PI);
        c.save();
        c.scale(esc, esc, 12f, 12f);
        trazo(2f);
        rf.set(5f, 4.5f, 19f, 18f);
        c.drawArc(rf, 180f, 180f, false, p);
        relleno();
        rf.set(4f, 12f, 8f, 19.5f);
        c.drawRoundRect(rf, 1.6f, 1.6f, p);
        rf.set(16f, 12f, 20f, 19.5f);
        c.drawRoundRect(rf, 1.6f, 1.6f, p);
        c.restore();
    }

    private void nota(Canvas c) {
        relleno();
        c.drawCircle(8.5f, 17.5f, 3.2f, p);
        trazo(1.8f);
        c.drawLine(11.3f, 17.2f, 11.3f, 5.5f, p);
        relleno();
        path.reset();
        path.moveTo(11.3f, 5.5f);
        path.lineTo(18f, 8f);
        path.lineTo(18f, 11.5f);
        path.lineTo(11.3f, 9f);
        path.close();
        c.drawPath(path, p);
    }

    private void check(Canvas c) {
        trazo(1.8f);
        p.setColor(conAlfa(color, 0.35f));
        c.drawCircle(12f, 12f, 9f, p);
        trazo(2.4f);
        path.reset();
        path.moveTo(7.5f, 12.5f);
        path.lineTo(10.8f, 15.8f);
        path.lineTo(16.8f, 8.8f);
        PathMeasure pm = new PathMeasure(path, false);
        parcial.reset();
        pm.getSegment(0f, pm.getLength() * anim, parcial, true);
        c.drawPath(parcial, p);
    }
}
