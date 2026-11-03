package com.lancelot.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * Cristal liquido para la isla (estilo "Liquid Glass").
 *
 * La refraccion del borde (shader AGSL "lente") y la idea del borde de luz, del brillo que sigue
 * al dedo y de los botones que se hinchan al pulsarlos estan tomadas de AndroidLiquidGlass
 * (Kyant0, https://github.com/Kyant0/AndroidLiquidGlass, Apache License 2.0), adaptadas para
 * dibujar dentro de la isla sin necesitar el fondo real de la pantalla.
 *
 * Todo esta protegido: si el shader no compila o el sistema no lo admite, la isla sigue con el
 * borde de luz y el brillo (solo con Canvas) y nada falla.
 */
final class Cristal {

    private Cristal() {
    }

    /** El shader de refraccion se puede usar (Android 13+ y sin errores al crearlo). */
    static volatile boolean shaderOk = Build.VERSION.SDK_INT >= 33;
    /** Fuerza general del cristal, 0..1 (ajuste "Intensidad del cristal"). */
    static volatile float fuerza = 0.7f;
    /** Angulo de la luz en radianes (45 grados = luz desde arriba a la izquierda). */
    static volatile float luz = (float) (Math.PI / 4.0);

    /** Refraccion en el borde: el contenido cerca del borde se curva como en una lente. */
    private static final String LENTE = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float dispersion;\n"
            + "\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float r) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(r));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - r;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float r) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(r));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n"
            + "\n"
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 c = coord - halfSize;\n"
            + "    float sd = sdRoundedRect(c, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - (-sd) / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(c, halfSize, gradRadius));\n"
            + "    float2 rc = coord + d * grad;\n"
            + "    if (dispersion > 0.0) {\n"
            + "        float di = dispersion * ((c.x * c.y) / (halfSize.x * halfSize.y));\n"
            + "        float2 ds = d * grad * di;\n"
            + "        half4 cr = content.eval(rc + ds);\n"
            + "        half4 cg = content.eval(rc);\n"
            + "        half4 cb = content.eval(rc - ds);\n"
            + "        return half4(cr.r, cg.g, cb.b, (cr.a + cg.a + cb.a) / 3.0);\n"
            + "    }\n"
            + "    return content.eval(rc);\n"
            + "}\n";

    static void fallo(Throwable t) {
        shaderOk = false;
        Registro.log("[IslaSystemUI] cristal: refraccion desactivada (" + t + ")");
    }

    private static float lim(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    private static int blanco(float alfa) {
        int a = Math.round(lim(alfa, 0f, 1f) * 255f);
        return (a << 24) | 0x00FFFFFF;
    }

    /** Version diminuta (24x24) de una imagen: al estirarla queda como un desenfoque muy fuerte. */
    static Bitmap pequena(Drawable d) {
        try {
            if (d == null) return null;
            Rect old = d.copyBounds();
            Bitmap b1 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 128, 128);
            d.draw(new Canvas(b1));
            d.setBounds(old);
            Bitmap b2 = Bitmap.createScaledBitmap(b1, 48, 48, true);
            Bitmap b3 = Bitmap.createScaledBitmap(b2, 24, 24, true);
            return b3;
        } catch (Throwable t) {
            Registro.log(t);
            return null;
        }
    }

    // ------------------------------------------------------------------ inclinacion del telefono

    private static final ArrayList<BrilloView> sigue = new ArrayList<>();
    private static boolean sensorOn;
    private static SensorManager sm;
    private static float fx;
    private static final SensorEventListener oyente = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            try {
                fx += (e.values[0] - fx) * 0.12f;
                float t = lim(fx / 6f, -1f, 1f);
                // la luz se queda "fija en el mundo": al girar el telefono el brillo se desplaza
                float ang = (float) (Math.PI / 4.0) - t * 0.7f;
                if (Math.abs(ang - luz) > 0.012f) {
                    luz = ang;
                    for (int i = 0; i < sigue.size(); i++) sigue.get(i).invalidate();
                }
            } catch (Throwable t) {
                // sin inclinacion
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {
        }
    };

    private static void registrar(Context c, BrilloView v) {
        if (!sigue.contains(v)) sigue.add(v);
        if (sensorOn) return;
        try {
            sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (s == null) return;
            sensorOn = sm.registerListener(oyente, s, SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable t) {
            Registro.log(t);
        }
    }

    private static void soltar(BrilloView v) {
        sigue.remove(v);
        if (sigue.isEmpty() && sensorOn) {
            try {
                if (sm != null) sm.unregisterListener(oyente);
            } catch (Throwable t) {
                Registro.log(t);
            }
            sensorOn = false;
            luz = (float) (Math.PI / 4.0);
            fx = 0f;
        }
    }

    // ------------------------------------------------------------------ lente (refraccion)

    /** Dibuja un fondo y lo curva en el borde. Con ref, dibuja una copia del fondo de la tarjeta. */
    static final class LenteView extends View {
        private final Drawable fondo;
        private final View ref;
        private final int tinte;
        private RuntimeShader sh;
        private float radio;
        private float altura;
        private float cantidad;
        private float dispersion;
        private int ultW = -1;
        private int ultH = -1;
        private float ultR = -1f;
        private float ultA = -1f;
        private float ultC = -1f;
        private final int[] pos = new int[2];

        LenteView(Context c, Drawable fondo, View ref, int tinte) {
            super(c);
            this.fondo = fondo;
            this.ref = ref;
            this.tinte = tinte;
            // la lente principal avisa al fondo para repintarse (radio o portada nuevos)
            if (ref == null && fondo != null) fondo.setCallback(this);
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == fondo || super.verifyDrawable(who);
        }

        void config(float altura, float cantidad, float dispersion) {
            this.altura = altura;
            this.cantidad = cantidad;
            this.dispersion = dispersion;
            actualizarFx();
        }

        void poner(float r) {
            if (Math.abs(r - radio) < 0.4f) return;
            radio = r;
            actualizarFx();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            actualizarFx();
        }

        private void actualizarFx() {
            int w = getWidth();
            int h = getHeight();
            boolean quiere = shaderOk && fuerza > 0.01f && cantidad > 0f && altura > 0f && w > 8
                    && h > 8;
            if (!quiere) {
                if (ultW != -2) {
                    ultW = -2;
                    try {
                        setRenderEffect(null);
                    } catch (Throwable t) {
                        // nada
                    }
                }
                return;
            }
            if (w == ultW && h == ultH && radio == ultR && altura == ultA && cantidad == ultC) {
                return;
            }
            try {
                if (sh == null) sh = new RuntimeShader(LENTE);
                float m = Math.min(w, h);
                float alt = Math.min(altura, m * 0.45f);
                sh.setFloatUniform("size", (float) w, (float) h);
                sh.setFloatUniform("radius", Math.min(radio, m / 2f));
                sh.setFloatUniform("refractionHeight", alt);
                sh.setFloatUniform("refractionAmount", -cantidad * fuerza);
                sh.setFloatUniform("dispersion", fuerza > 0.5f ? dispersion : 0f);
                setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"));
                ultW = w;
                ultH = h;
                ultR = radio;
                ultA = altura;
                ultC = cantidad;
            } catch (Throwable t) {
                fallo(t);
                try {
                    setRenderEffect(null);
                } catch (Throwable t2) {
                    // nada
                }
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (fondo == null) return;
            if (ref == null) {
                fondo.setBounds(0, 0, getWidth(), getHeight());
                fondo.draw(c);
            } else if (ref.getWidth() > 0 && ref.getHeight() > 0) {
                // copia del fondo de la tarjeta, colocada tal cual esta detras del boton
                float x = 0f;
                float y = 0f;
                View v = this;
                int guardia = 0;
                while (v != null && v != ref && guardia++ < 12) {
                    x += v.getLeft() + v.getTranslationX();
                    y += v.getTop() + v.getTranslationY();
                    if (!(v.getParent() instanceof View)) break;
                    v = (View) v.getParent();
                }
                c.save();
                c.translate(-x, -y);
                fondo.setBounds(0, 0, ref.getWidth(), ref.getHeight());
                fondo.draw(c);
                c.restore();
            }
            if (tinte != 0) c.drawColor(tinte);
        }
    }

    // ------------------------------------------------------------------ brillo del borde

    /** Borde de luz (reflejo especular), reflejo suave de arriba y brillo que sigue al dedo. */
    static final class BrilloView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final boolean sigueLuz;
        private final float base;
        private final float densidad;
        private final int[] cols = new int[25];
        private final float[] pos = new float[25];
        private float radio;
        private float pulso;
        private float tx;
        private float ty;
        private ValueAnimator anim;

        BrilloView(Context c, boolean sigueLuz, float base) {
            super(c);
            this.sigueLuz = sigueLuz;
            this.base = base;
            this.densidad = c.getResources().getDisplayMetrics().density;
            for (int i = 0; i < pos.length; i++) pos[i] = i / (float) (pos.length - 1);
        }

        void poner(float r) {
            if (Math.abs(r - radio) < 0.4f) return;
            radio = r;
            invalidate();
        }

        void ponerPulso(float v) {
            pulso = v;
            invalidate();
        }

        /** El dedo se apoya (true) o se levanta (false): el brillo aparece y se apaga suave. */
        void pulsar(boolean abajo, float x, float y) {
            if (abajo) {
                tx = x;
                ty = y;
            }
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(pulso, abajo ? 1f : 0f);
            anim.setDuration(abajo ? 140 : 360);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pulso = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (sigueLuz) registrar(getContext(), this);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (sigueLuz) soltar(this);
            if (anim != null) anim.cancel();
        }

        /** Intensidad de la luz segun hacia donde mira el borde (phi, en radianes). */
        private float luzEn(float phi, float ang) {
            return Math.abs((float) Math.cos(phi - ang));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w < 6f || h < 6f) return;
            float a = lim(fuerza * 1.25f, 0f, 1f) * base * (1f + 0.4f * lim(pulso, 0f, 1f));
            if (a <= 0.01f) return;
            float r = Math.min(radio, Math.min(w, h) / 2f);
            float ang = sigueLuz ? luz : (float) (Math.PI / 4.0);

            // reflejo suave en la parte de arriba
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0f, 0f, 0f, h * 0.55f, blanco(0.10f * a), 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            c.drawRect(0f, 0f, w, h, p);

            // brillo bajo el dedo
            float pu = lim(pulso, 0f, 1f);
            if (pu > 0.01f) {
                float rad = Math.max(w, h) * 0.55f;
                p.setShader(new RadialGradient(tx, ty, Math.max(rad, 8f), blanco(0.22f * pu * a),
                        0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRect(0f, 0f, w, h, p);
            }
            p.setShader(null);

            // borde de luz: ancho y suave
            borde(c, w, h, r, densidad * 3.4f, 0.16f * a, ang);
            borde(c, w, h, r, densidad * 1.1f, 0.85f * a, ang);
        }

        private void borde(Canvas c, float w, float h, float r, float ancho, float alfa,
                           float ang) {
            float m = ancho / 2f;
            float x0 = m;
            float y0 = m;
            float x1 = w - m;
            float y1 = h - m;
            float rr = Math.max(0f, r - m);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(ancho);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setShader(null);
            double pi = Math.PI;
            // lados rectos: el borde mira siempre al mismo lado, luz constante
            recta(c, x0 + rr, y0, x1 - rr, y0, luzEn((float) (-pi / 2), ang) * alfa);
            recta(c, x0 + rr, y1, x1 - rr, y1, luzEn((float) (pi / 2), ang) * alfa);
            recta(c, x0, y0 + rr, x0, y1 - rr, luzEn((float) pi, ang) * alfa);
            recta(c, x1, y0 + rr, x1, y1 - rr, luzEn(0f, ang) * alfa);
            if (rr > 0.5f) {
                float d = 2f * rr;
                arco(c, x0, y0, d, 180f, alfa, ang);
                arco(c, x1 - d, y0, d, 270f, alfa, ang);
                arco(c, x1 - d, y1 - d, d, 0f, alfa, ang);
                arco(c, x0, y1 - d, d, 90f, alfa, ang);
            }
            p.setShader(null);
        }

        private void recta(Canvas c, float xa, float ya, float xb, float yb, float alfa) {
            if (Math.abs(xa - xb) < 0.5f && Math.abs(ya - yb) < 0.5f) return;
            p.setColor(blanco(alfa));
            c.drawLine(xa, ya, xb, yb, p);
        }

        /** Esquina: en un arco la direccion del borde coincide con el angulo, asi que va en barrido. */
        private void arco(Canvas c, float left, float top, float d, float inicio, float alfa,
                          float ang) {
            for (int i = 0; i < cols.length; i++) {
                float phi = (float) Math.toRadians(i * 360f / (cols.length - 1));
                cols[i] = blanco(luzEn(phi, ang) * alfa);
            }
            rf.set(left, top, left + d, top + d);
            p.setShader(new SweepGradient(rf.centerX(), rf.centerY(), cols, pos));
            c.drawArc(rf, inicio, 90f, false, p);
        }
    }

    // ------------------------------------------------------------------ boton de cristal

    /**
     * Boton redondo de vidrio: refracta una copia del fondo de la tarjeta, se hincha al pulsarlo
     * (con rebote), sigue un poco al dedo y brilla.
     */
    static final class BotonView extends FrameLayout {
        private final LenteView lente;
        private final BrilloView brillo;
        private final float diam;
        private final float maxOff;
        private ValueAnimator anim;
        private float pulso;
        private float dx0;
        private float dy0;

        BotonView(Context c, Drawable fondoCard, View ref, int diametro) {
            super(c);
            diam = diametro;
            maxOff = diametro * 0.12f;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            setClipToOutline(true);
            lente = new LenteView(c, fondoCard, ref, 0x24FFFFFF);
            lente.poner(diam / 2f);
            lente.config(diam * 0.24f, diam * 0.40f, 1f);
            addView(lente, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            brillo = new BrilloView(c, true, 0.8f);
            brillo.poner(diam / 2f);
            addView(brillo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        void icono(View v, int tam) {
            addView(v, new FrameLayout.LayoutParams(tam, tam, Gravity.CENTER));
        }

        private void animar(boolean abajo) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(pulso, abajo ? 1f : 0f);
            anim.setDuration(abajo ? 190 : 320);
            anim.setInterpolator(abajo ? new OvershootInterpolator(1.9f)
                    : new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pulso = (Float) a.getAnimatedValue();
                    float s = 1f + 0.24f * pulso;
                    setScaleX(s);
                    setScaleY(s);
                    lente.config(diam * 0.24f * (1f + 0.5f * pulso),
                            diam * 0.40f * (1f + 0.9f * pulso), 1f);
                    brillo.ponerPulso(pulso);
                }
            });
            anim.start();
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            try {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx0 = ev.getRawX();
                        dy0 = ev.getRawY();
                        animar(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - dx0;
                        float dy = ev.getRawY() - dy0;
                        setTranslationX(maxOff * (float) Math.tanh(0.06f * dx / maxOff));
                        setTranslationY(maxOff * (float) Math.tanh(0.06f * dy / maxOff));
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        animar(false);
                        animate().translationX(0f).translationY(0f).setDuration(260)
                                .setInterpolator(new OvershootInterpolator(1.6f)).start();
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                Registro.log(t);
            }
            return super.onTouchEvent(ev);
        }
    }
}
