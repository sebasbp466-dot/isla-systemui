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
    static final int TELEFONO = 12;
    static final int CAMARA = 13;
    static final int MICROFONO = 14;
    static final int AVION = 15;
    static final int LUNA = 16;
    static final int AHORRO = 17;
    static final int VOLUMEN = 18;
    static final int PORTAPAPELES = 19;
    static final int DESCARGA = 20;
    static final int COLGAR = 21;
    static final int MENSAJE = 22;
    static final int WIFI = 23;
    static final int BLUETOOTH = 24;
    static final int UBICACION = 25;

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

        boolean pulsa = activo && (tipo == LINTERNA || tipo == BATERIA || tipo == RAYO
                || tipo == TELEFONO || tipo == CAMARA || tipo == MICROFONO || tipo == DESCARGA
                || tipo == UBICACION);
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
        ondas(c);
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
            case TELEFONO:
                telefono(c, false);
                break;
            case COLGAR:
                telefono(c, true);
                break;
            case CAMARA:
                camara(c);
                break;
            case MICROFONO:
                microfono(c);
                break;
            case AVION:
                avion(c);
                break;
            case LUNA:
                luna(c);
                break;
            case AHORRO:
                ahorro(c);
                break;
            case VOLUMEN:
                volumen(c);
                break;
            case PORTAPAPELES:
                portapapeles(c);
                break;
            case DESCARGA:
                descarga(c);
                break;
            case MENSAJE:
                mensaje(c);
                break;
            case WIFI:
                wifi(c);
                break;
            case BLUETOOTH:
                bluetooth(c);
                break;
            case UBICACION:
                ubicacion(c);
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

    // ------------------------------------------------------------------ iconos nuevos

    private void telefono(Canvas c, boolean colgar) {
        float ang;
        if (colgar) {
            ang = 135f;
        } else if (activo && modo == 1) {
            // Llamada entrante: el telefono vibra
            ang = 14f * seno() * (float) Math.sin(pulso * Math.PI * 6.0);
        } else {
            ang = -26f * (1f - anim);
        }
        c.save();
        c.rotate(ang, 12f, 12f);
        relleno();
        p.setColor(colgar ? ROJO : color);
        path.reset();
        path.moveTo(6.62f, 10.79f);
        path.cubicTo(8.06f, 13.62f, 10.38f, 15.93f, 13.21f, 17.38f);
        path.lineTo(15.41f, 15.18f);
        path.cubicTo(15.68f, 14.91f, 16.08f, 14.82f, 16.43f, 14.94f);
        path.cubicTo(17.55f, 15.31f, 18.76f, 15.51f, 20f, 15.51f);
        path.cubicTo(20.55f, 15.51f, 21f, 15.96f, 21f, 16.51f);
        path.lineTo(21f, 20f);
        path.cubicTo(21f, 20.55f, 20.55f, 21f, 20f, 21f);
        path.cubicTo(10.61f, 21f, 3f, 13.39f, 3f, 4f);
        path.cubicTo(3f, 3.45f, 3.45f, 3f, 4f, 3f);
        path.lineTo(7.5f, 3f);
        path.cubicTo(8.05f, 3f, 8.5f, 3.45f, 8.5f, 4f);
        path.cubicTo(8.5f, 5.25f, 8.7f, 6.45f, 9.07f, 7.57f);
        path.cubicTo(9.18f, 7.92f, 9.10f, 8.31f, 8.82f, 8.59f);
        path.lineTo(6.62f, 10.79f);
        path.close();
        c.drawPath(path, p);
        c.restore();
    }

    private void camara(Canvas c) {
        float esc = 0.8f + 0.2f * anim;
        c.save();
        c.scale(esc, esc, 12f, 12f);
        relleno();
        p.setColor(color);
        rf.set(8.5f, 4.5f, 15.5f, 8f);
        c.drawRoundRect(rf, 1.2f, 1.2f, p);
        rf.set(2.5f, 6.8f, 21.5f, 19.5f);
        c.drawRoundRect(rf, 2.6f, 2.6f, p);
        p.setColor(0xCC000000);
        c.drawCircle(12f, 13.2f, 4.2f, p);
        p.setColor(conAlfa(Color.WHITE, 0.9f));
        c.drawCircle(12f, 13.2f, 2.5f, p);
        c.restore();
        if (activo) {
            relleno();
            p.setColor(conAlfa(ROJO, 0.55f + 0.45f * (0.5f + 0.5f * seno())));
            c.drawCircle(19f, 9.4f, 1.1f, p);
        }
    }

    private void microfono(Canvas c) {
        float esc = 0.8f + 0.2f * anim;
        c.save();
        c.scale(esc, esc, 12f, 12f);
        relleno();
        p.setColor(color);
        rf.set(8.7f, 2.8f, 15.3f, 14.2f);
        c.drawRoundRect(rf, 3.3f, 3.3f, p);
        trazo(1.9f);
        rf.set(5.8f, 6.5f, 18.2f, 17.5f);
        c.drawArc(rf, 0f, 180f, false, p);
        c.drawLine(12f, 17.5f, 12f, 21f, p);
        c.drawLine(8.8f, 21f, 15.2f, 21f, p);
        c.restore();
        if (activo) {
            float a = 0.5f + 0.5f * seno();
            trazo(1.5f);
            p.setColor(conAlfa(color, 0.35f + 0.5f * a));
            rf.set(3.2f, 4.2f, 20.8f, 19.8f);
            c.drawArc(rf, -35f, 70f, false, p);
            c.drawArc(rf, 145f, 70f, false, p);
        }
    }

    private void avion(Canvas c) {
        float slide = 1f - anim;
        c.save();
        c.translate(-9f * slide, 9f * slide);
        c.rotate(45f, 12f, 12f);
        relleno();
        p.setColor(conAlfa(color, Math.min(1f, 0.25f + anim)));
        path.reset();
        path.moveTo(21f, 16f);
        path.lineTo(21f, 14f);
        path.lineTo(13f, 9f);
        path.lineTo(13f, 3.5f);
        path.cubicTo(13f, 2.67f, 12.33f, 2f, 11.5f, 2f);
        path.cubicTo(10.67f, 2f, 10f, 2.67f, 10f, 3.5f);
        path.lineTo(10f, 9f);
        path.lineTo(2f, 14f);
        path.lineTo(2f, 16f);
        path.lineTo(10f, 13.5f);
        path.lineTo(10f, 19f);
        path.lineTo(8f, 20.5f);
        path.lineTo(8f, 22f);
        path.lineTo(11.5f, 21f);
        path.lineTo(15f, 22f);
        path.lineTo(15f, 20.5f);
        path.lineTo(13f, 19f);
        path.lineTo(13f, 13.5f);
        path.close();
        c.drawPath(path, p);
        c.restore();
        if (!activo) {
            trazo(2f);
            p.setColor(ROJO);
            c.drawLine(4.5f, 4.5f, 19.5f, 19.5f, p);
        }
    }

    private final Path luna2 = new Path();

    private void luna(Canvas c) {
        c.save();
        c.rotate(-40f * (1f - anim), 12f, 12f);
        path.reset();
        path.addCircle(12f, 12f, 8.6f, Path.Direction.CW);
        luna2.reset();
        luna2.addCircle(16.2f, 8.4f, 7.2f, Path.Direction.CW);
        path.op(luna2, Path.Op.DIFFERENCE);
        relleno();
        p.setColor(color);
        c.drawPath(path, p);
        c.restore();
    }

    private void ahorro(Canvas c) {
        trazo(1.5f);
        p.setColor(conAlfa(Color.WHITE, 0.9f));
        rf.set(2.5f, 6.5f, 19.5f, 17.5f);
        c.drawRoundRect(rf, 2.6f, 2.6f, p);
        relleno();
        p.setColor(conAlfa(Color.WHITE, 0.9f));
        rf.set(20.3f, 10f, 22f, 14f);
        c.drawRoundRect(rf, 0.8f, 0.8f, p);
        float esc = 0.4f + 0.6f * anim;
        c.save();
        c.scale(esc, esc, 11f, 12f);
        trazo(2.2f);
        p.setColor(color);
        c.drawLine(11f, 8.6f, 11f, 15.4f, p);
        c.drawLine(7.6f, 12f, 14.4f, 12f, p);
        c.restore();
    }

    private void volumen(Canvas c) {
        relleno();
        p.setColor(Color.WHITE);
        path.reset();
        path.moveTo(3.5f, 9.5f);
        path.lineTo(7.5f, 9.5f);
        path.lineTo(12.5f, 5f);
        path.lineTo(12.5f, 19f);
        path.lineTo(7.5f, 14.5f);
        path.lineTo(3.5f, 14.5f);
        path.close();
        c.drawPath(path, p);
        if (nivel <= 0.01f) {
            trazo(2f);
            p.setColor(ROJO);
            c.drawLine(15.5f, 9.5f, 20.5f, 14.5f, p);
            c.drawLine(20.5f, 9.5f, 15.5f, 14.5f, p);
            return;
        }
        trazo(1.8f);
        p.setColor(conAlfa(color, anim));
        rf.set(9.5f, 8f, 17f, 16f);
        c.drawArc(rf, -55f, 110f, false, p);
        if (nivel > 0.45f) {
            rf.set(7f, 4.8f, 21f, 19.2f);
            c.drawArc(rf, -55f, 110f, false, p);
        }
    }

    private void portapapeles(Canvas c) {
        float dy = 2f * (1f - anim);
        trazo(1.7f);
        rf.set(5f, 5f + dy, 19f, 21f);
        c.drawRoundRect(rf, 2.2f, 2.2f, p);
        relleno();
        rf.set(8.7f, 2.8f + dy, 15.3f, 6.8f + dy);
        c.drawRoundRect(rf, 1.4f, 1.4f, p);
        trazo(1.6f);
        p.setColor(conAlfa(color, 0.8f));
        c.drawLine(8.5f, 11.5f + dy, 15.5f, 11.5f + dy, p);
        c.drawLine(8.5f, 15f + dy, 13.5f, 15f + dy, p);
    }

    private void descarga(Canvas c) {
        float rebote = activo ? 1.8f * (0.5f + 0.5f * seno()) : 0f;
        trazo(2.2f);
        p.setColor(color);
        c.drawLine(12f, 3.5f + rebote, 12f, 14.5f + rebote, p);
        path.reset();
        path.moveTo(7f, 10f + rebote);
        path.lineTo(12f, 15f + rebote);
        path.lineTo(17f, 10f + rebote);
        c.drawPath(path, p);
        p.setColor(conAlfa(color, 0.7f));
        c.drawLine(5f, 20.2f, 19f, 20.2f, p);
    }

    private void mensaje(Canvas c) {
        float esc = 0.85f + 0.15f * anim;
        c.save();
        c.scale(esc, esc, 12f, 12f);
        relleno();
        p.setColor(color);
        rf.set(3f, 4f, 21f, 17f);
        c.drawRoundRect(rf, 4f, 4f, p);
        path.reset();
        path.moveTo(7f, 16f);
        path.lineTo(7f, 21f);
        path.lineTo(12.5f, 16.5f);
        path.close();
        c.drawPath(path, p);
        p.setColor(0x66000000);
        c.drawCircle(8f, 10.5f, 1.2f, p);
        c.drawCircle(12f, 10.5f, 1.2f, p);
        c.drawCircle(16f, 10.5f, 1.2f, p);
        c.restore();
    }

    // ------------------------------------------------------------------ ondas y mas iconos

    /** Ondas que se expanden desde el icono mientras esta activo (linterna, llamada, camara...). */
    private void ondas(Canvas c) {
        if (!activo || animPulso == null) return;
        boolean ok = tipo == LINTERNA || tipo == BATERIA || tipo == CAMARA || tipo == MICROFONO
                || tipo == UBICACION || (tipo == TELEFONO && modo == 1);
        if (!ok) return;
        int col = tipo == LINTERNA ? AMARILLO : (tipo == BATERIA ? VERDE : color);
        for (int k = 0; k < 2; k++) {
            float f = (pulso + k * 0.5f) % 1f;
            trazo(1.1f);
            p.setColor(conAlfa(col, 0.38f * (1f - f)));
            c.drawCircle(12f, 12f, 6.5f + 5.5f * f, p);
        }
    }

    private void wifi(Canvas c) {
        float a = anim;
        trazo(2f);
        p.setColor(color);
        float[] r = {4f, 7.5f, 11f};
        for (int i = 0; i < 3; i++) {
            float alfa = Math.max(0f, Math.min(1f, a * 3f - (2 - i)));
            p.setAlpha(Math.round(255f * (activo ? alfa : 0.35f + 0.65f * alfa)));
            rf.set(12f - r[i], 19f - r[i], 12f + r[i], 19f + r[i]);
            c.drawArc(rf, 225f, 90f, false, p);
        }
        relleno();
        p.setColor(color);
        c.drawCircle(12f, 18.2f, 1.5f, p);
        if (!activo) {
            trazo(2f);
            p.setColor(ROJO);
            c.drawLine(4.5f, 4.5f, 19.5f, 19.5f, p);
        }
    }

    private void bluetooth(Canvas c) {
        float esc = 0.8f + 0.2f * anim;
        c.save();
        c.scale(esc, esc, 12f, 12f);
        trazo(2f);
        p.setColor(color);
        path.reset();
        path.moveTo(7f, 8f);
        path.lineTo(17f, 16f);
        path.lineTo(12f, 21f);
        path.lineTo(12f, 3f);
        path.lineTo(17f, 8f);
        path.lineTo(7f, 16f);
        c.drawPath(path, p);
        c.restore();
    }

    private void ubicacion(Canvas c) {
        float rebote = (float) Math.abs(Math.sin(anim * Math.PI * 2.0)) * 2f * (1f - anim);
        c.save();
        c.translate(0f, -rebote);
        relleno();
        p.setColor(color);
        path.reset();
        path.addCircle(12f, 9.5f, 6.3f, Path.Direction.CW);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(6.4f, 12.2f);
        path.lineTo(12f, 21.5f);
        path.lineTo(17.6f, 12.2f);
        path.close();
        c.drawPath(path, p);
        p.setColor(0xCC000000);
        c.drawCircle(12f, 9.5f, 2.4f, p);
        c.restore();
    }
}
