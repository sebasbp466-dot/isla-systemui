package com.lancelot.island;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/**
 * Fondo de la isla, dibujado en una sola pasada y sin reservar memoria al animarse.
 *
 * Estilo 0 (Hyper): negro profundo con un degradado sutil y un reborde de luz arriba.
 * Estilo 1 (Cristal): tinte translucido con brillo diagonal y un borde de luz que se apaga
 * por los lados (si hay desenfoque real detras, se le suma desde IslandView).
 * En ambos, un "cometa" de luz del color del evento puede dar la vuelta al borde.
 */
final class FondoDrawable extends Drawable {

    private final int estilo;
    private final int color;
    private final float borde;
    private final float luzAncho;

    private final Paint relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sombra = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reborde = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint luzP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Matrix m = new Matrix();
    private final Matrix mc = new Matrix();

    private final LinearGradient degradado;
    private final LinearGradient rebordeG;
    private SweepGradient cometa;
    private int cometaColor;

    private float radio;
    private boolean conBlur;

    private boolean luzActiva;
    private float luzAngulo;
    private float luzAlfa;

    FondoDrawable(int estilo, int color, float density) {
        this.estilo = estilo;
        this.color = color;
        this.borde = Math.max(1f, density * (estilo == 1 ? 1.2f : 1f));
        this.luzAncho = Math.max(2f, density * 2f);

        relleno.setStyle(Paint.Style.FILL);
        sombra.setStyle(Paint.Style.FILL);
        reborde.setStyle(Paint.Style.STROKE);
        reborde.setStrokeWidth(borde);
        luzP.setStyle(Paint.Style.STROKE);
        luzP.setStrokeCap(Paint.Cap.ROUND);

        if (estilo == 1) {
            // Brillo en diagonal: se enciende arriba a la izquierda y un poco abajo a la derecha
            degradado = new LinearGradient(0f, 0f, 1f, 1f,
                    new int[]{0x4DFFFFFF, 0x10FFFFFF, 0x00FFFFFF, 0x1AFFFFFF},
                    new float[]{0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP);
            // Borde: luz fuerte en dos esquinas opuestas, casi nada en el resto
            rebordeG = new LinearGradient(0f, 0f, 1f, 1f,
                    new int[]{0xE6FFFFFF, 0x1FFFFFFF, 0x1AFFFFFF, 0x99FFFFFF},
                    new float[]{0f, 0.32f, 0.68f, 1f}, Shader.TileMode.CLAMP);
        } else {
            degradado = new LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{0x1CFFFFFF, 0x00FFFFFF, 0x14000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            rebordeG = new LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{0x38FFFFFF, 0x0DFFFFFF, 0x06FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
        sombra.setShader(degradado);
        reborde.setShader(rebordeG);
    }

    void setRadio(float px) {
        radio = px;
        invalidateSelf();
    }

    /** Con desenfoque real detras el tinte puede ser mas transparente. */
    void setConBlur(boolean v) {
        conBlur = v;
        invalidateSelf();
    }

    /** Enciende el cometa de luz del color dado; angulo 0..360 y alfa 0..1. */
    void setLuz(int c, float angulo, float alfa) {
        if (alfa <= 0f) {
            luzActiva = false;
            invalidateSelf();
            return;
        }
        if (cometa == null || cometaColor != c) {
            int t = c & 0x00FFFFFF;
            cometa = new SweepGradient(0.5f, 0.5f,
                    new int[]{t, t, 0xFF000000 | t, 0xAA000000 | t, t},
                    new float[]{0f, 0.5f, 0.78f, 0.82f, 1f});
            cometaColor = c;
        }
        luzActiva = true;
        luzAngulo = angulo;
        luzAlfa = Math.min(1f, alfa);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        if (w <= 0f || h <= 0f) return;
        float r = Math.min(radio, Math.min(w, h) / 2f);

        // Relleno
        int tinte;
        if (estilo == 1) {
            int a = Color_alpha(color);
            // Con blur detras basta un tinte ligero; sin blur se necesita mas cuerpo
            int tope = conBlur ? 0x73 : 0xB3;
            tinte = (color & 0x00FFFFFF) | (Math.min(a, tope) << 24);
        } else {
            tinte = color;
        }
        relleno.setColor(tinte);
        rf.set(b.left, b.top, b.right, b.bottom);
        c.drawRoundRect(rf, r, r, relleno);

        // Brillo / profundidad
        m.setScale(w, h);
        m.postTranslate(b.left, b.top);
        degradado.setLocalMatrix(m);
        c.drawRoundRect(rf, r, r, sombra);

        // Reborde de luz
        float mi = borde / 2f;
        rf.set(b.left + mi, b.top + mi, b.right - mi, b.bottom - mi);
        float rr = Math.max(0f, r - mi);
        rebordeG.setLocalMatrix(m);
        c.drawRoundRect(rf, rr, rr, reborde);

        // Cometa de luz del evento
        if (luzActiva && cometa != null) {
            float lm = luzAncho / 2f;
            rf.set(b.left + lm, b.top + lm, b.right - lm, b.bottom - lm);
            float lr = Math.max(0f, r - lm);
            mc.setRotate(luzAngulo, 0.5f, 0.5f);
            mc.postScale(w, h);
            mc.postTranslate(b.left, b.top);
            cometa.setLocalMatrix(mc);
            luzP.setShader(cometa);
            // Halo ancho y suave, y encima el nucleo brillante
            luzP.setStrokeWidth(luzAncho * 2.6f);
            luzP.setAlpha(Math.round(70f * luzAlfa));
            c.drawRoundRect(rf, lr, lr, luzP);
            luzP.setStrokeWidth(luzAncho);
            luzP.setAlpha(Math.round(255f * luzAlfa));
            c.drawRoundRect(rf, lr, lr, luzP);
        }
    }

    private static int Color_alpha(int c) {
        return (c >>> 24) & 0xFF;
    }

    @Override
    public void setAlpha(int alpha) {
        // La opacidad la lleva la vista
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        // sin filtro
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
