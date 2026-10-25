package com.lancelot.island;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/**
 * Fondo de la isla. Cada estilo tiene su propia personalidad:
 *
 * Hyper (0): negro profundo. Por dentro lleva un "aura": una luz del color del evento que nace
 * junto al icono y respira, y un filo de color en el borde de abajo. Solo emite luz, no brilla.
 *
 * Cristal (1): vidrio liquido. Tinte translucido (con desenfoque real detras si la ROM deja),
 * borde doble tipo lente, brillo diagonal, un reflejo que barre la superficie al llegar un evento
 * y una luz de color que se refracta desde abajo.
 *
 * En ambos, un cometa de luz puede dar la vuelta al borde. Todo se dibuja sin reservar memoria.
 */
final class FondoDrawable extends Drawable {

    private final int estilo;
    private final int color;
    private final float borde;
    private final float luzAncho;
    private final float density;

    private final Paint relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sombra = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reborde = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint luzP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint auraP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint filoP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflejoP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path recorte = new Path();
    private final Matrix m = new Matrix();
    private final Matrix mc = new Matrix();
    private final Matrix ma = new Matrix();

    private final LinearGradient degradado;
    private final LinearGradient rebordeG;
    private final LinearGradient reflejoG;
    private SweepGradient cometa;
    private int cometaColor;
    private RadialGradient auraG;
    private LinearGradient filoG;
    private int auraColor;

    private float radio;
    private boolean conBlur;

    private boolean luzActiva;
    private float luzAngulo;
    private float luzAlfa;

    private float auraNivel;
    private float reflejoPos = -1f;

    FondoDrawable(int estilo, int color, float density) {
        this.estilo = estilo;
        this.color = color;
        this.density = density;
        this.borde = Math.max(1f, density * (estilo == 1 ? 1.2f : 1f));
        this.luzAncho = Math.max(2f, density * 2f);

        relleno.setStyle(Paint.Style.FILL);
        sombra.setStyle(Paint.Style.FILL);
        auraP.setStyle(Paint.Style.FILL);
        reflejoP.setStyle(Paint.Style.FILL);
        reborde.setStyle(Paint.Style.STROKE);
        reborde.setStrokeWidth(borde);
        filoP.setStyle(Paint.Style.STROKE);
        filoP.setStrokeWidth(Math.max(1.5f, density * 1.6f));
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
            // Reflejo que barre la superficie
            reflejoG = new LinearGradient(0f, 0f, 1f, 0f,
                    new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        } else {
            degradado = new LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{0x16FFFFFF, 0x00FFFFFF, 0x14000000},
                    new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
            rebordeG = new LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{0x30FFFFFF, 0x0AFFFFFF, 0x06FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            reflejoG = null;
        }
        sombra.setShader(degradado);
        reborde.setShader(rebordeG);
        if (reflejoG != null) reflejoP.setShader(reflejoG);
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

    /**
     * Luz interior del color del evento. nivel 0..1 (0 = apagada).
     * Hyper: nace junto al icono. Cristal: se refracta desde abajo.
     */
    void setAura(int c, float nivel) {
        if (nivel <= 0f) {
            if (auraNivel != 0f) {
                auraNivel = 0f;
                invalidateSelf();
            }
            return;
        }
        if (auraG == null || auraColor != c) {
            int t = c & 0x00FFFFFF;
            auraG = new RadialGradient(0f, 0f, 1f, new int[]{0xFF000000 | t, 0x00000000 | t},
                    null, Shader.TileMode.CLAMP);
            filoG = new LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{t, t, 0xFF000000 | t},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            auraColor = c;
            auraP.setShader(auraG);
            filoP.setShader(filoG);
        }
        auraNivel = Math.min(1f, nivel);
        invalidateSelf();
    }

    /** Posicion del reflejo de cristal: 0..1 cruza la isla; fuera de ese rango no se ve. */
    void setReflejo(float f) {
        reflejoPos = f;
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
            int a = (color >>> 24) & 0xFF;
            // Con blur detras basta un tinte ligero; sin blur se necesita mas cuerpo
            int tope = conBlur ? 0x73 : 0xB3;
            tinte = (color & 0x00FFFFFF) | (Math.min(a, tope) << 24);
        } else {
            tinte = color;
        }
        relleno.setColor(tinte);
        rf.set(b.left, b.top, b.right, b.bottom);
        c.drawRoundRect(rf, r, r, relleno);

        boolean recortar = auraNivel > 0f || reflejoPos >= 0f;
        if (recortar) {
            recorte.rewind();
            recorte.addRoundRect(rf, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(recorte);
        }

        // Luz interior
        if (auraNivel > 0f && auraG != null) {
            float radioAura;
            float cx;
            float cy;
            if (estilo == 1) {
                // Cristal: la luz sube desde abajo, ancha y suave
                radioAura = Math.max(w * 0.55f, h * 1.6f);
                cx = b.left + w * 0.5f;
                cy = b.bottom + h * 0.15f;
                auraP.setAlpha(Math.round(120f * auraNivel));
            } else {
                // Hyper: nace junto al icono, a la izquierda
                radioAura = Math.max(h * 2.6f, w * 0.38f);
                cx = b.left + h * 0.85f;
                cy = b.top + h * 0.5f;
                auraP.setAlpha(Math.round(135f * auraNivel));
            }
            ma.setScale(radioAura, radioAura);
            ma.postTranslate(cx, cy);
            auraG.setLocalMatrix(ma);
            c.drawRect(rf, auraP);
        }

        // Reflejo de cristal que barre la superficie
        if (reflejoG != null && reflejoPos >= 0f && reflejoPos <= 1f) {
            float banda = Math.max(w * 0.42f, h * 2f);
            float x = b.left - banda + reflejoPos * (w + banda * 1.2f);
            c.save();
            c.rotate(16f, b.left + w / 2f, b.top + h / 2f);
            ma.setScale(banda, 1f);
            ma.postTranslate(x, 0f);
            reflejoG.setLocalMatrix(ma);
            c.drawRect(x, b.top - h, x + banda, b.bottom + h, reflejoP);
            c.restore();
        }

        if (recortar) c.restore();

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
        reborde.setAlpha(255);
        c.drawRoundRect(rf, rr, rr, reborde);

        if (estilo == 1) {
            // Segundo borde, mas tenue y hacia dentro: da grosor de lente
            float in = density * 2.6f;
            rf.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
            float ri = Math.max(0f, r - in);
            if (rf.width() > 4f && rf.height() > 4f) {
                reborde.setAlpha(70);
                c.drawRoundRect(rf, ri, ri, reborde);
                reborde.setAlpha(255);
            }
        } else if (auraNivel > 0f && filoG != null) {
            // Hyper: un filo del color del evento en el borde de abajo
            float fm = filoP.getStrokeWidth() / 2f;
            rf.set(b.left + fm, b.top + fm, b.right - fm, b.bottom - fm);
            float rf2 = Math.max(0f, r - fm);
            filoG.setLocalMatrix(m);
            filoP.setAlpha(Math.round(190f * auraNivel));
            c.drawRoundRect(rf, rf2, rf2, filoP);
        }

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
