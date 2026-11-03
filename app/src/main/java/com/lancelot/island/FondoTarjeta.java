package com.lancelot.island;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Fondo de las tarjetas del motor Xiaomi/iPhone: color liso, brillo de musica o halo de linterna. */
final class FondoTarjeta extends Drawable {

    static final int LISO = 0;
    static final int MUSICA = 1;
    static final int LINTERNA = 2;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rf = new RectF();
    private int base = 0xFF111A21;
    private float radio;
    private int modo = LISO;
    private int acento = 0xFF3A5AFF;
    private float gx;
    private float gy;
    private float gr;
    /** Portada diminuta (24x24) que se estira y queda como un fondo desenfocado (estilo Liquid). */
    private Bitmap cover;
    private final Paint pc = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();

    void setBase(int c) {
        base = c;
        invalidateSelf();
    }

    void setRadio(float r) {
        radio = r;
        invalidateSelf();
    }

    void setModo(int m, int acento) {
        modo = m;
        this.acento = acento;
        invalidateSelf();
    }

    void setCover(Bitmap b) {
        cover = b;
        if (b != null) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.55f);
            pc.setColorFilter(new ColorMatrixColorFilter(cm));
        }
        invalidateSelf();
    }

    void setHalo(float x, float y, float r) {
        gx = x;
        gy = y;
        gr = r;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        rf.set(getBounds());
        float w = rf.width();
        float h = rf.height();
        if (w <= 0 || h <= 0) return;
        float r = Math.min(radio, Math.min(w, h) / 2f);
        clip.reset();
        clip.addRoundRect(rf, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(base);
        c.drawRect(rf, p);
        if (cover != null && !cover.isRecycled()) {
            float bw = cover.getWidth();
            float bh = cover.getHeight();
            float esc = Math.max(w / bw, h / bh);
            float sw = w / esc;
            float sh = h / esc;
            src.set(Math.round((bw - sw) / 2f), Math.round((bh - sh) / 2f),
                    Math.round((bw + sw) / 2f), Math.round((bh + sh) / 2f));
            pc.setAlpha(235);
            c.drawBitmap(cover, src, rf, pc);
            // se oscurece un poco para que el texto se lea siempre
            p.setShader(new LinearGradient(0f, rf.top, 0f, rf.bottom, 0x70000000, 0x3A000000,
                    Shader.TileMode.CLAMP));
            c.drawRect(rf, p);
        }
        if (modo == MUSICA) {
            int fuerte = (acento & 0x00FFFFFF) | (cover != null ? 0x66000000 : 0xC0000000);
            int nada = acento & 0x00FFFFFF;
            p.setShader(new LinearGradient(0f, rf.top + h * 0.34f, 0f, rf.bottom,
                    nada, fuerte, Shader.TileMode.CLAMP));
            c.drawRect(rf, p);
            p.setShader(new RadialGradient(rf.right - w * 0.12f, rf.bottom, w * 0.55f,
                    (acento & 0x00FFFFFF) | 0x66000000, nada, Shader.TileMode.CLAMP));
            c.drawRect(rf, p);
        } else if (modo == LINTERNA && gr > 0f) {
            p.setShader(new RadialGradient(rf.left + gx, rf.top + gy, gr,
                    new int[]{0x55FFFFFF, 0x1EFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(rf, p);
        }
        p.setShader(null);
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
