package com.lancelot.island;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * Motor de los estilos Xiaomi (Hyper Island) e iPhone, copiado del comportamiento de los videos:
 * capsula doble (pildora + circulo), tarjetas de cronometro, linterna, musica y llamada,
 * banners con la animacion de "encoger y estirar", y cambios con desenfoque entre tarjetas.
 * Todas las medidas estan tomadas de un lienzo de 720 px de ancho y se escalan a la pantalla.
 */
final class Isla2 {

    private static Isla2 inst;
    /** Si el motor falla varias veces seguidas se desactiva y vuelve el diseño anterior. */
    static volatile boolean roto;
    private int fallos;

    static synchronized Isla2 get(Context c) {
        if (inst == null) inst = new Isla2(c);
        return inst;
    }

    // ------------------------------------------------------------------ curvas y resortes

    /** Curva muestreada a 30 fps (datos medidos del video), interpolada con Catmull-Rom. */
    private static final class Curva {
        final float[] v;
        static final float DT = 1000f / 30f;

        Curva(float... v) {
            this.v = v;
        }

        float durMs() {
            return (v.length - 1) * DT;
        }

        float en(float ms) {
            if (ms <= 0f) return v[0];
            float x = ms / DT;
            int i = (int) x;
            if (i >= v.length - 1) return v[v.length - 1];
            float t = x - i;
            float p0 = v[Math.max(0, i - 1)];
            float p1 = v[i];
            float p2 = v[i + 1];
            float p3 = v[Math.min(v.length - 1, i + 2)];
            return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t
                    + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t);
        }
    }

    /** Ancho del banner al estirarse desde la capsula (0 = capsula, 1 = banner). */
    private static final Curva BAN_ANCHO_IN = new Curva(0f, -0.071f, -0.214f, 0.058f, 0.286f,
            0.597f, 0.747f, 0.864f, 0.968f, 1.039f, 1.058f, 1.078f, 1.071f, 1.058f, 1.039f,
            1.026f, 1.019f, 1.006f, 1f);
    private static final Curva BAN_ALTO_IN = new Curva(1f, 1f, 0.88f, 0.82f, 0.88f, 0.94f, 1f);
    /** Y al volver (1 = banner, 0 = capsula), pasandose por debajo de la capsula. */
    private static final Curva BAN_ANCHO_OUT = new Curva(1f, 0.74f, 0.20f, -0.136f, -0.123f,
            -0.032f, 0.0065f, 0.019f, 0.026f, 0.005f, 0f);
    private static final Curva BAN_ALTO_OUT = new Curva(1f, 0.94f, 0.82f, 0.88f, 0.94f, 1f);
    /** La isla sale de la camara: ancho y alto relativos al tamano final (medido del video). */
    private static final Curva EMER_W = new Curva(0f, 0.214f, 0.429f, 0.619f, 0.738f, 0.869f,
            0.952f, 1f, 1.036f, 1.048f, 1.048f, 1.036f, 1.024f, 1.012f, 1f);
    private static final Curva EMER_H = new Curva(0f, 0.31f, 0.62f, 0.69f, 0.85f, 0.92f, 0.92f,
            1f);
    /** Y vuelve a meterse en la camara: ancho (1 = actual, 0.17 = hueco) y alto relativos. */
    private static final Curva SAL_W = new Curva(1f, 0.98f, 0.81f, 0.67f, 0.52f, 0.40f, 0.29f,
            0.28f, 0.22f, 0.21f, 0.19f, 0.18f, 0.18f, 0.17f);
    private static final Curva SAL_H = new Curva(1f, 1f, 0.91f, 0.86f, 0.71f, 0.66f, 0.6f, 0.6f,
            0.57f, 0.54f, 0.54f, 0.54f, 0.54f, 0.54f);

    private static final class Muelle implements TimeInterpolator {
        private final float amort;
        private final float freq;

        Muelle(float amort, float freq) {
            this.amort = amort;
            this.freq = freq;
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            return (float) (1.0 - Math.exp(-amort * t) * Math.cos(2.0 * Math.PI * freq * t));
        }
    }

    /**
     * Muelle fisico con los mismos parametros que usa Xiaomi (miuix): respuesta en segundos y
     * razon de amortiguacion. pos() da el desplazamiento respecto al destino en el instante t.
     */
    private static final class Resorte {
        private final float w;
        private final float z;

        Resorte(float respuesta, float amort) {
            this.w = (float) (2.0 * Math.PI / respuesta);
            this.z = amort;
        }

        float pos(float x0, float v0, float t) {
            if (z < 1f) {
                float wd = w * (float) Math.sqrt(1f - z * z);
                float e = (float) Math.exp(-z * w * t);
                return e * (x0 * (float) Math.cos(wd * t)
                        + ((v0 + z * w * x0) / wd) * (float) Math.sin(wd * t));
            }
            float e = (float) Math.exp(-w * t);
            return e * (x0 + (v0 + w * x0) * t);
        }
    }

    // ------------------------------------------------------------------ estado

    private static final int TIPO_SOBRE_BARRA = 2024; // TYPE_NAVIGATION_BAR_PANEL
    private static final int MAX_RECIENTES = 6;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float density;
    private IslaConfig cfg;
    private boolean ios;
    private boolean pantallaOn = true;
    private int tipoVentana = TIPO_SOBRE_BARRA;

    // Elementos fijos (los mantiene el controlador y los copia aqui)
    private Evento llamada;
    private Evento crono;
    private Evento linterna;
    private Evento musica;
    private boolean musicaSilenciada;
    /** Elementos fijos ocultados al deslizarlos (bit por tipo); vuelven al anunciarse de nuevo. */
    private int ocultos;

    // Lo que hay en pantalla ahora
    private Evento banner;      // banner o capsula de aviso sobre la pildora
    private Evento cardEv;      // tarjeta abierta (de un fijo o de una notificacion)
    private boolean cardVivo;   // la tarjeta es de un elemento fijo
    private Evento trans;       // evento puntual en curso
    private final ArrayList<Evento> recientes = new ArrayList<>();
    private long ultimaNotif;
    private boolean respondiendo;
    private boolean panelAcciones;

    private Runnable bannerPend;
    private Runnable cardPend;
    private int gen;

    // Medidas (px)
    private int scrW;
    private float k;
    private int sbPx;
    private int cutW;
    private int cutH;
    private int cutCxAbs;
    private int hP;        // alto de la pildora
    private int topP;      // y de la pildora
    private int idleW;
    private int gap;
    private int pillCx;
    private int cardX;
    private int cardW;
    private int cardTop;
    private int bleed;
    private int banW;

    // Vistas
    private FrameLayout root;
    private FrameLayout inner;
    private Pieza pMain;
    private Pieza pCirc;
    private Pieza pCard;
    private int winX;
    private int winW;
    private int winH;
    private boolean ventanaAmplia;
    private int animsActivas;
    private final ArrayList<Pieza> piezasSalientes = new ArrayList<>();

    private Isla2(Context c) {
        Context app = c.getApplicationContext();
        ctx = app != null ? app : c;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        density = ctx.getResources().getDisplayMetrics().density;
        cfg = IslaConfig.get(ctx);
    }

    // ------------------------------------------------------------------ API publica

    /** El controlador activa el motor y le pasa lo que ya hubiera fijo. */
    void activar(Evento ll, Evento cr, Evento li, Evento mu, boolean muSil) {
        llamada = ll;
        crono = cr;
        linterna = li;
        musica = mu;
        musicaSilenciada = muSil;
        seguro(new Runnable() {
            @Override
            public void run() {
                cfg = IslaConfig.get(ctx);
                aplicar();
            }
        });
    }

    void apagar() {
        seguro(new Runnable() {
            @Override
            public void run() {
                quitarTodo();
                banner = null;
                cardEv = null;
                trans = null;
                cardVivo = false;
            }
        });
    }

    void reaplicar() {
        seguro(new Runnable() {
            @Override
            public void run() {
                cfg = IslaConfig.get(ctx);
                // Se rehace entero con los ajustes nuevos (estilo, colores, tamaños)
                quitarTodo();
                aplicar();
            }
        });
    }

    void pantalla(final boolean on) {
        seguro(new Runnable() {
            @Override
            public void run() {
                pantallaOn = on;
                if (!on) {
                    quitarTodo();
                } else {
                    aplicar();
                }
            }
        });
    }

    void mostrar(final Evento e) {
        seguro(new Runnable() {
            @Override
            public void run() {
                mostrarInterno(e);
            }
        });
    }

    void progreso(final Evento e, final boolean nuevo) {
        seguro(new Runnable() {
            @Override
            public void run() {
                cfg = IslaConfig.get(ctx);
                if (!cfg.enabled || !cfg.permite(e.tipo) || !pantallaOn) return;
                if (refrescarInterno(e)) return;
                if (nuevo) {
                    e.minimizada = true;
                    mostrarInterno(e);
                }
            }
        });
    }

    void refrescar(final Evento e) {
        seguro(new Runnable() {
            @Override
            public void run() {
                refrescarInterno(e);
            }
        });
    }

    void fijar(final int tipo, final Evento m, final boolean anunciar) {
        seguro(new Runnable() {
            @Override
            public void run() {
                fijarInterno(tipo, m, anunciar);
            }
        });
    }

    void setMusicaSilenciada(boolean s) {
        musicaSilenciada = s;
    }

    private void seguro(final Runnable r) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (roto) return;
                try {
                    r.run();
                    fallos = 0;
                } catch (Throwable t) {
                    XposedBridge.log("[IslaSystemUI] motor2: " + t);
                    XposedBridge.log(t);
                    fallos++;
                    if (fallos >= 3) {
                        roto = true;
                        try {
                            quitarTodo();
                        } catch (Throwable t2) {
                            XposedBridge.log(t2);
                        }
                        try {
                            IslandController.motorRoto();
                        } catch (Throwable t3) {
                            XposedBridge.log(t3);
                        }
                    } else {
                        try {
                            quitarTodo();
                        } catch (Throwable t2) {
                            XposedBridge.log(t2);
                        }
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ flujo de eventos

    private boolean esBanner(Evento e) {
        switch (e.tipo) {
            case Evento.CARGA:
            case Evento.TIMBRE:
            case Evento.AURIC:
            case Evento.BATERIA:
            case Evento.AJUSTE:
            case Evento.VOLUMEN:
            case Evento.PRIVACIDAD:
            case Evento.PORTAPAPELES:
            case Evento.PROGRESO:
                return true;
            default:
                return false;
        }
    }

    private boolean horizontalOculto() {
        try {
            return cfg.ocultarH && ctx.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean enNoMolestar() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.getCurrentInterruptionFilter()
                    > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean decidirMinimizada(Evento e) {
        if (e.minimizada) return true;
        if (e.tipo != Evento.NOTIF) return false;
        if (e.pkg != null && cfg.isMin(e.pkg)) return true;
        if (cfg.notifMode == 1) return true;
        if (cfg.notifMode == 0) return false;
        if (cfg.noMolestarMin && enNoMolestar()) return true;
        boolean ocupada = vivos().size() > 0 || trans != null;
        boolean rafaga = ultimaNotif != 0
                && SystemClock.elapsedRealtime() - ultimaNotif < 8000;
        return ocupada || rafaga;
    }

    private void registrar(Evento e) {
        if (e.tipo == Evento.MUSICA || e.tipo == Evento.LLAMADA || e.tipo == Evento.LINTERNA
                || e.tipo == Evento.CRONO || e.tipo == Evento.IDLE) {
            return;
        }
        if (e.clave != null) {
            for (int i = 0; i < recientes.size(); i++) {
                Evento r = recientes.get(i);
                if (r.tipo == e.tipo && e.clave.equals(r.clave)) {
                    recientes.set(i, e);
                    return;
                }
            }
        }
        if (!recientes.contains(e)) recientes.add(e);
        while (recientes.size() > MAX_RECIENTES) recientes.remove(0);
    }

    private void mostrarInterno(Evento e) {
        cfg = IslaConfig.get(ctx);
        if (!cfg.enabled || !cfg.permite(e.tipo) || !pantallaOn || horizontalOculto()) return;

        // El volumen se actualiza en el sitio mientras sigues pulsando los botones
        if (e.tipo == Evento.VOLUMEN && banner != null && banner.tipo == Evento.VOLUMEN) {
            banner = e;
            trans = e;
            rehacerContenido();
            programarBanner(1800);
            return;
        }
        // Mensajes seguidos de la misma app: se agrupan
        if (e.tipo == Evento.NOTIF && trans != null && trans.tipo == Evento.NOTIF
                && e.pkg != null && e.pkg.equals(trans.pkg)
                && SystemClock.elapsedRealtime() - ultimaNotif < 20000) {
            e.agrupadas = trans.agrupadas + 1;
            e.minimizada = trans.minimizada;
            e.contador = trans.contador;
            ultimaNotif = SystemClock.elapsedRealtime();
            registrar(e);
            if (banner == trans) banner = e;
            if (cardEv == trans && !cardVivo) cardEv = e;
            trans = e;
            rehacerContenido();
            if (cardEv == e) {
                programarCard(duracionCard(e));
            } else {
                programarBanner(duracionBanner(e));
            }
            return;
        }

        boolean min = decidirMinimizada(e);
        registrar(e);
        if (e.tipo == Evento.NOTIF) {
            ultimaNotif = SystemClock.elapsedRealtime();
            int n = 0;
            for (Evento r : recientes) {
                if (r.tipo == Evento.NOTIF) n++;
            }
            e.contador = n;
        }
        // Hay una tarjeta abierta por el usuario y llega algo discreto: no se interrumpe
        if (min && cardEv != null && cardVivo) {
            pulso();
            return;
        }
        e.minimizada = min;
        trans = e;
        panelAcciones = false;
        respondiendo = false;
        if (esBanner(e) || min) {
            banner = e;
            if (cardEv != null && !cardVivo) cardEv = null;
            programarBanner(duracionBanner(e));
        } else {
            cardEv = e;
            cardVivo = false;
            banner = null;
            cancelar(bannerPend);
            bannerPend = null;
            programarCard(duracionCard(e));
        }
        if (cfg.haptic) vibrar(false);
        aplicar();
    }

    private int duracionBanner(Evento e) {
        int ms = e.duracionMs > 0 ? e.duracionMs : (e.minimizada && e.tipo == Evento.NOTIF
                ? Math.max(3200, cfg.durationMs - 600) : cfg.durationMs);
        return Math.min(ms, ios ? 2800 : 3200);
    }

    private int duracionCard(Evento e) {
        int ms = e.duracionMs > 0 ? e.duracionMs
                : (e.tipo == Evento.MUSICA ? 6000 : cfg.durationMs);
        if (e.tipo == Evento.LLAMADA && e.sub == 1) ms = 0;
        if (e.botonTxt != null && e.botonTxt.length > 0 && e.tipo != Evento.LLAMADA) {
            ms = Math.max(ms, 6000);
        }
        return ms;
    }

    private boolean refrescarInterno(Evento e) {
        boolean cambio = false;
        if (banner != null && banner.tipo == e.tipo && e.mismaClave(banner)) {
            e.minimizada = banner.minimizada;
            if (trans == banner) trans = e;
            banner = e;
            cambio = true;
        }
        if (cardEv != null && !cardVivo && cardEv.tipo == e.tipo && e.mismaClave(cardEv)) {
            e.minimizada = cardEv.minimizada;
            if (trans == cardEv) trans = e;
            cardEv = e;
            cambio = true;
        }
        if (cambio) rehacerContenido();
        return cambio;
    }

    private void fijarInterno(int tipo, Evento m, boolean anunciar) {
        cfg = IslaConfig.get(ctx);
        if (tipo == Evento.MUSICA) {
            musica = m;
        } else if (tipo == Evento.LINTERNA) {
            linterna = m;
        } else if (tipo == Evento.CRONO) {
            crono = m;
        } else {
            llamada = m;
        }
        if (m == null) {
            ocultos &= ~(1 << tipo);
            if (cardVivo && cardEv != null && cardEv.tipo == tipo) {
                cardEv = null;
                cardVivo = false;
                cancelar(cardPend);
                cardPend = null;
            }
            aplicar();
            return;
        }
        if (cardVivo && cardEv != null && cardEv.tipo == tipo) {
            boolean eraEntrante = cardEv.tipo == Evento.LLAMADA && cardEv.sub == 1;
            cardEv = m;
            if (eraEntrante && m.sub != 1) programarCard(4000);
            rehacerContenido();
        }
        if (!cfg.enabled || !cfg.permite(tipo) || !pantallaOn || horizontalOculto()) {
            aplicar();
            return;
        }
        if (anunciar) {
            ocultos &= ~(1 << tipo);
            if (tipo == Evento.MUSICA) musicaSilenciada = false;
            boolean importante = tipo == Evento.LLAMADA || tipo == Evento.LINTERNA
                    || tipo == Evento.CRONO;
            boolean ocupado = cardEv != null || banner != null;
            if (importante || !ocupado) {
                if (enVivos(m)) {
                    abrirCardVivo(m, duracionCard(m));
                    return;
                }
            }
        }
        aplicar();
    }

    /** Pulso breve del circulo cuando llega algo discreto con la tarjeta abierta. */
    private void pulso() {
        if (pCirc == null) return;
        pCirc.capa.animate().scaleX(1.18f).scaleY(1.18f).setDuration(110)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (pCirc != null) {
                            pCirc.capa.animate().scaleX(1f).scaleY(1f).setDuration(220)
                                    .setInterpolator(new Muelle(6f, 1.4f)).start();
                        }
                    }
                }).start();
    }

    private boolean oculto(int tipo) {
        return (ocultos & (1 << tipo)) != 0;
    }

    private boolean enVivos(Evento m) {
        return vivos().contains(m);
    }

    /** Elementos fijos en orden de prioridad: llamada, cronometro, linterna, musica. */
    private ArrayList<Evento> vivos() {
        ArrayList<Evento> v = new ArrayList<>();
        if (llamada != null && cfg.fLlamada) v.add(llamada);
        if (crono != null && cfg.fCrono && !oculto(Evento.CRONO)) v.add(crono);
        if (linterna != null && cfg.fLinterna && !oculto(Evento.LINTERNA)) v.add(linterna);
        if (musica != null && !musicaSilenciada && cfg.fMusica && cfg.permite(Evento.MUSICA)) {
            v.add(musica);
        }
        return v;
    }

    private void abrirCardVivo(Evento m, int ms) {
        trans = null;
        banner = null;
        cancelar(bannerPend);
        bannerPend = null;
        cardEv = m;
        cardVivo = true;
        panelAcciones = false;
        respondiendo = false;
        programarCard(ms);
        if (cfg.haptic) vibrar(false);
        aplicar();
    }

    // ------------------------------------------------------------------ temporizadores

    private void cancelar(Runnable r) {
        if (r != null) h.removeCallbacks(r);
    }

    private void programarBanner(int ms) {
        cancelar(bannerPend);
        bannerPend = new Runnable() {
            @Override
            public void run() {
                try {
                    if (banner == null) return;
                    if (trans == banner) trans = null;
                    banner = null;
                    aplicar();
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        if (ms > 0) h.postDelayed(bannerPend, ms);
    }

    private void programarCard(int ms) {
        cancelar(cardPend);
        cardPend = null;
        if (ms <= 0) return;
        cardPend = new Runnable() {
            @Override
            public void run() {
                try {
                    cerrarCard();
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        h.postDelayed(cardPend, ms);
    }

    private void reiniciarCard() {
        if (cardEv == null || cardPend == null) return;
        programarCard(Math.max(3000, duracionCard(cardEv)));
    }

    private void cerrarCard() {
        cancelar(cardPend);
        cardPend = null;
        if (cardEv == null) return;
        if (!cardVivo && trans == cardEv) trans = null;
        cardEv = null;
        cardVivo = false;
        panelAcciones = false;
        if (respondiendo) finalizarRespuestaUI();
        aplicar();
    }

    private void vibrar(boolean fuerte) {
        try {
            Vibrator v = ctx.getSystemService(Vibrator.class);
            if (v == null) return;
            v.vibrate(VibrationEffect.createPredefined(fuerte ? VibrationEffect.EFFECT_CLICK
                    : VibrationEffect.EFFECT_TICK));
        } catch (Throwable ignorado) {
            // sin vibrador
        }
    }

    // ------------------------------------------------------------------ medidas

    private int px(float v) {
        return Math.round(v * k);
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private void medir() {
        sbPx = 0;
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) sbPx = ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignorado) {
            // valor de reserva
        }
        if (sbPx <= 0) sbPx = dp(24);
        cutW = 0;
        cutH = 0;
        cutCxAbs = 0;
        int cutCy = 0;
        scrW = ctx.getResources().getDisplayMetrics().widthPixels;
        try {
            WindowMetrics m = wm.getCurrentWindowMetrics();
            scrW = m.getBounds().width();
            DisplayCutout dc = m.getWindowInsets().getDisplayCutout();
            if (dc != null) {
                Rect r = dc.getBoundingRectTop();
                if (r != null && !r.isEmpty()) {
                    cutW = r.width();
                    cutH = r.bottom;
                    cutCxAbs = r.centerX();
                    cutCy = r.centerY();
                }
            }
        } catch (Throwable ignorado) {
            // sin datos del notch
        }
        k = scrW / 720f;
        if (cutW <= 0) {
            cutCxAbs = scrW / 2;
            cutCy = Math.max(px(30), sbPx / 2);
        }
        hP = px(61);
        topP = Math.max(0, cutCy - hP / 2);
        if (topP + hP < cutH + px(3)) hP = cutH + px(3) - topP;
        idleW = Math.max(px(198), cutW + px(84));
        gap = px(9);
        pillCx = cutCxAbs;
        bleed = px(28);
        cardX = px(25);
        cardW = scrW - 2 * cardX;
        cardTop = topP + hP + px(16);
        banW = Math.max(Math.round(scrW * 0.637f), idleW + px(70));
    }

    private int anchoTexto(String s, float tamPx, boolean negrita) {
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(tamPx);
        p.setTypeface(negrita ? Typeface.DEFAULT_BOLD : Typeface.create("sans-serif-medium",
                Typeface.NORMAL));
        return (int) Math.ceil(p.measureText(s));
    }

    /** Ancho de la pildora principal segun lo que lleve dentro. */
    private int anchoItem(Evento e) {
        if (e == null) return idleW;
        switch (e.tipo) {
            case Evento.CRONO:
                return idleW + anchoTexto(textoReloj(e, true), px(27), false) - px(6) + px(12);
            case Evento.LLAMADA:
                if (e.sub == 2 && e.base > 0) {
                    return idleW + anchoTexto(textoReloj(e, true), px(27), false) - px(6) + px(12);
                }
                return idleW;
            case Evento.MUSICA:
                return idleW + px(40);
            default:
                return idleW;
        }
    }

    private int alturaCard(Evento e) {
        if (e == null) return px(154);
        if (e.tipo == Evento.MUSICA) return px(308);
        return px(154);
    }

    private float radioCard(int alto) {
        return Math.min(alto / 2f, px(ios ? 66 : 62));
    }

    // ------------------------------------------------------------------ ventana

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                winW, winH, tipoVentana,
                (respondiendo ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = winX;
        lp.y = 0;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("IslaSystemUI");
        return lp;
    }

    private void crearVentana() {
        medir();
        root = new FrameLayout(ctx);
        inner = new FrameLayout(ctx);
        inner.setClipChildren(false);
        root.addView(inner, new FrameLayout.LayoutParams(scrW, topP + hP + px(40)));
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                try {
                    return toque(ev);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                    return false;
                }
            }
        });
        winX = Math.max(0, pillCx - idleW / 2 - px(10));
        winW = idleW + px(20);
        winH = topP + hP + px(10);
        ventanaAmplia = false;
        try {
            wm.addView(root, params());
        } catch (Throwable t) {
            XposedBridge.log("[IslaSystemUI] motor2: ventana sobre la barra no disponible: " + t);
            tipoVentana = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            wm.addView(root, params());
        }
        inner.setTranslationX(-winX);
        XposedBridge.log("[IslaSystemUI] motor2: ventana anadida (notch=" + cutW + "px, k=" + k
                + ")");
    }

    private void ponerVentana(int x, int w, int alto) {
        if (root == null) return;
        if (x == winX && w == winW && alto == winH) return;
        winX = x;
        winW = w;
        winH = alto;
        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) inner.getLayoutParams();
        ilp.width = scrW;
        ilp.height = alto;
        inner.setLayoutParams(ilp);
        inner.setTranslationX(-x);
        try {
            wm.updateViewLayout(root, params());
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Ajusta la ventana: pequeña en reposo (solo la fila), ancha con tarjeta o banner. */
    private void ajustarVentana() {
        if (root == null) return;
        boolean amplia = cardEv != null || pCard != null || banner != null
                || (pMain != null && pMain.clase == 2) || animsActivas > 0 || arrastrando
                || !piezasSalientes.isEmpty();
        int bottom = topP + hP + px(12);
        if (arrastrando) bottom += dp(24);
        if (pCard != null || cardEv != null) {
            int ch = alturaCard(cardEv != null ? cardEv : (pCard != null ? pCard.ev : null));
            bottom = Math.max(bottom, cardTop + Math.max(ch, pCard != null ? pCard.h : 0)
                    + px(16));
        }
        if (amplia) {
            ponerVentana(0, scrW, bottom);
        } else {
            int mw = pMain != null ? pMain.w : idleW;
            int left = pillCx - mw / 2;
            int right = pillCx + mw / 2;
            if (pCirc != null && pCirc.vivo) right = Math.max(right, pCirc.x + pCirc.w);
            int m = px(10);
            ponerVentana(Math.max(0, left - m), Math.min(scrW, right + m) - Math.max(0, left - m),
                    topP + hP + px(10));
        }
    }

    // ------------------------------------------------------------------ piezas y capas

    /** Contenido de una pieza: se puede desenfocar y llevar sus relojes. */
    private final class Capa extends FrameLayout {
        final ArrayList<Runnable> ticks = new ArrayList<>();

        Capa() {
            super(ctx);
        }
    }

    private static void desenfocar(View v, float px) {
        if (android.os.Build.VERSION.SDK_INT < 31 || v == null) return;
        try {
            android.graphics.RenderEffect fx = px < 0.5f ? null
                    : android.graphics.RenderEffect.createBlurEffect(px, px,
                    android.graphics.Shader.TileMode.CLAMP);
            v.setRenderEffect(fx);
        } catch (Throwable t) {
            // sin desenfoque
        }
    }

    private interface Paso {
        void f(float t);
    }

    private interface Fin {
        void f(boolean cancelada);
    }

    private ValueAnimator tween(long delay, long dur, TimeInterpolator ip, final Paso p,
                                final Fin fin) {
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setStartDelay(Math.max(0, delay));
        va.setDuration(Math.max(1, dur));
        va.setInterpolator(ip != null ? ip : new android.view.animation.LinearInterpolator());
        animsActivas++;
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                try {
                    if (p != null) p.f((Float) a.getAnimatedValue());
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            private boolean cancelada;
            private boolean hecho;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelada = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (hecho) return;
                hecho = true;
                animsActivas = Math.max(0, animsActivas - 1);
                try {
                    if (!cancelada && p != null) p.f(1f);
                    if (fin != null) fin.f(cancelada);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                if (animsActivas == 0) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ajustarVentana();
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });
                }
            }
        });
        va.start();
        return va;
    }

    private float ts() {
        return 100f / Math.max(30, cfg.speed);
    }

    private final class Pieza {
        final FrameLayout capa;
        final FrameLayout cont;
        final Drawable fondo;
        int x;
        int y;
        int w;
        int h;
        float r;
        int clase;
        int objW;
        String sig = "";
        Evento ev;
        Capa actual;
        boolean vivo = true;
        ValueAnimator rectAnim;
        ValueAnimator vuelta;
        float blur;
        final ArrayList<Capa> salientes = new ArrayList<>();

        Pieza(Drawable fondo) {
            this.fondo = fondo;
            capa = new FrameLayout(ctx);
            capa.setClipChildren(false);
            capa.setClipToPadding(false);
            capa.setPadding(bleed, bleed, bleed, bleed);
            cont = new FrameLayout(ctx);
            cont.setBackground(fondo);
            cont.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            cont.setClipToOutline(true);
            capa.addView(cont, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        void colocar(int nx, int ny, int nw, int nh, float nr) {
            x = nx;
            y = ny;
            w = Math.max(1, nw);
            h = Math.max(1, nh);
            r = nr;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) capa.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(w + 2 * bleed, h + 2 * bleed);
            }
            lp.width = w + 2 * bleed;
            lp.height = h + 2 * bleed;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = x - bleed;
            lp.topMargin = y - bleed;
            capa.setLayoutParams(lp);
            if (fondo instanceof GradientDrawable) {
                ((GradientDrawable) fondo).setCornerRadius(r);
            } else if (fondo instanceof FondoTarjeta) {
                ((FondoTarjeta) fondo).setRadio(r);
            }
            cont.invalidateOutline();
        }

        void ponerCapa(Capa c, int cw, int ch) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ch,
                    Gravity.CENTER);
            cont.addView(c, lp);
        }

        /** Cambia el contenido; con anim, la capa vieja se desenfoca y sale y la nueva entra. */
        void cambiarCapa(final Capa nueva, int cw, int ch, boolean anim, long retraso, long ms) {
            final Capa vieja = actual;
            actual = nueva;
            if (nueva == null) {
                if (vieja != null) sacarCapa(vieja, anim);
                return;
            }
            ponerCapa(nueva, cw, ch);
            if (!anim) {
                if (vieja != null) cont.removeView(vieja);
                return;
            }
            nueva.setAlpha(0f);
            desenfocar(nueva, 14f);
            tween(retraso, ms, new DecelerateInterpolator(1.3f), new Paso() {
                @Override
                public void f(float t) {
                    nueva.setAlpha(t);
                    desenfocar(nueva, 14f * (1f - t));
                }
            }, new Fin() {
                @Override
                public void f(boolean c) {
                    nueva.setAlpha(1f);
                    desenfocar(nueva, 0f);
                }
            });
            if (vieja != null) sacarCapa(vieja, true);
        }

        void sacarCapa(final Capa vieja, boolean anim) {
            if (!anim) {
                cont.removeView(vieja);
                return;
            }
            salientes.add(vieja);
            tween(0, 100, null, new Paso() {
                @Override
                public void f(float t) {
                    vieja.setAlpha(1f - t);
                    desenfocar(vieja, 12f * t);
                }
            }, new Fin() {
                @Override
                public void f(boolean c) {
                    cont.removeView(vieja);
                    salientes.remove(vieja);
                }
            });
        }

        void cancelarRect() {
            if (rectAnim != null) {
                rectAnim.cancel();
                rectAnim = null;
            }
        }
    }

    private Pieza nuevaPildora() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF000000);
        Pieza p = new Pieza(g);
        return p;
    }

    private Pieza nuevaCard(Evento e) {
        FondoTarjeta f = new FondoTarjeta();
        f.setBase(ios ? 0xFF000000 : 0xFF111A21);
        if (e != null && e.tipo == Evento.MUSICA) {
            f.setModo(FondoTarjeta.MUSICA, acentoMusica(e));
        } else if (e != null && e.tipo == Evento.LINTERNA) {
            f.setModo(FondoTarjeta.LINTERNA, 0xFFFFFFFF);
            f.setHalo(px(75), px(40), px(135));
        }
        return new Pieza(f);
    }

    private int acentoMusica(Evento e) {
        int c = e.color;
        if (c == 0xFFFFFFFF || Color.alpha(c) == 0) return 0xFF3A5AFF;
        return c;
    }

    private void anadir(Pieza p) {
        inner.addView(p.capa);
    }

    private void quitarPieza(Pieza p) {
        if (p == null) return;
        p.vivo = false;
        p.cancelarRect();
        cancelarVuelta(p);
        try {
            inner.removeView(p.capa);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void quitarTodo() {
        gen++;
        cancelar(bannerPend);
        cancelar(cardPend);
        bannerPend = null;
        cardPend = null;
        h.removeCallbacks(reloj);
        relojActivo = false;
        if (root != null) {
            try {
                wm.removeViewImmediate(root);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        if (pMain != null) pMain.vivo = false;
        if (pCirc != null) pCirc.vivo = false;
        if (pCard != null) pCard.vivo = false;
        root = null;
        inner = null;
        pMain = null;
        pCirc = null;
        pCard = null;
        piezasSalientes.clear();
        ventanaAmplia = false;
        animsActivas = 0;
        respondiendo = false;
    }

    // ------------------------------------------------------------------ escena

    private void calcularFila(Evento[] f) {
        ArrayList<Evento> v = vivos();
        if (cardVivo && cardEv != null) {
            for (int i = 0; i < v.size(); i++) {
                if (v.get(i).tipo == cardEv.tipo) {
                    v.remove(i);
                    break;
                }
            }
        }
        f[0] = v.size() > 0 ? v.get(0) : null;
        f[1] = v.size() > 1 ? v.get(1) : null;
    }

    /** El contenido de algo ya visible cambio: aplicar() lo detecta por la referencia nueva. */
    private void rehacerContenido() {
        aplicar();
    }

    private void quitarSuave() {
        if (root == null) return;
        Pieza a = pMain;
        Pieza b = pCirc;
        Pieza c = pCard;
        pMain = null;
        pCirc = null;
        pCard = null;
        if (a != null) salirPildora(a);
        if (b != null) salirPildora(b);
        if (c != null) salirCard(c, false);
        h.removeCallbacks(reloj);
        relojActivo = false;
        final int g = ++gen;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g != gen) return;
                if (pMain == null && pCirc == null && pCard == null) quitarTodo();
            }
        }, (long) (620 * ts()));
    }

    private void aplicar() {
        cfg = IslaConfig.get(ctx);
        ios = cfg.style == 4;
        if (!cfg.enabled || !pantallaOn || horizontalOculto()) {
            quitarTodo();
            return;
        }
        Evento[] fila = new Evento[2];
        calcularFila(fila);
        boolean idle = cfg.permanente || cardEv != null || banner != null;
        if (banner == null && cardEv == null && fila[0] == null && !idle) {
            quitarSuave();
            return;
        }
        gen++;
        if (root == null) crearVentana();
        sincronizarMain(fila, idle);
        sincronizarCirc(fila);
        sincronizarCard();
        iniciarReloj();
        ajustarVentana();
    }

    private String sigDe(int clase, Evento e) {
        if (clase == 0 || e == null) return "0";
        if (clase == 1) return "1:" + e.tipo;
        return "2:" + e.tipo + ":" + (e.clave != null ? e.clave
                : String.valueOf(System.identityHashCode(e)));
    }

    private void sincronizarMain(Evento[] fila, boolean quiereIdle) {
        int dClase;
        Evento dEv;
        int dW;
        if (banner != null) {
            dClase = 2;
            dEv = banner;
            dW = banW;
        } else if (fila[0] != null) {
            dClase = 1;
            dEv = fila[0];
            dW = anchoItem(dEv);
        } else if (quiereIdle) {
            dClase = 0;
            dEv = null;
            dW = idleW;
        } else {
            dClase = -1;
            dEv = null;
            dW = idleW;
        }
        if (dClase < 0) {
            if (pMain != null) {
                Pieza p = pMain;
                pMain = null;
                salirPildora(p);
            }
            return;
        }
        String sg = sigDe(dClase, dEv);
        if (pMain == null) {
            Pieza p = nuevaPildora();
            pMain = p;
            p.clase = dClase;
            p.objW = dW;
            p.sig = sg;
            p.ev = dEv;
            p.colocar(pillCx - dW / 2, topP, dW, hP, hP / 2f);
            Capa c = contenidoPildora(dClase, dEv, dW, hP);
            p.ponerCapa(c, dW, hP);
            p.actual = c;
            anadir(p);
            entradaPildora(p);
            return;
        }
        if (pMain.sig.equals(sg)) {
            if (pMain.ev != dEv) {
                pMain.ev = dEv;
                Capa c = contenidoPildora(dClase, dEv, pMain.w, hP);
                pMain.cambiarCapa(c, dClase == 2 ? banW : dW, hP, false, 0, 0);
            }
            if (pMain.objW != dW && dClase != 2) anchoSuave(pMain, dW);
            return;
        }
        int antes = pMain.clase;
        pMain.sig = sg;
        pMain.clase = dClase;
        pMain.ev = dEv;
        cambiarPildora(pMain, antes, dClase, dEv, dW);
    }

    /**
     * La pildora nace del hueco de la camara (22 px de alto y 32 de ancho en el video) y se
     * estira con un pequeno rebote; el contenido aparece cuando ya hay sitio. El circulo del
     * segundo elemento conserva su pop breve.
     */
    private void entradaPildora(final Pieza p) {
        if (p.clase == 3) {
            entradaCirculo(p);
            return;
        }
        final int wT = p.w;
        final int hT = p.h;
        final int w0 = Math.min(wT, px(40));
        final int h0 = Math.min(hT, Math.round(hT * 22f / 35f));
        p.colocar(pillCx - w0 / 2, topP + (hT - h0) / 2, w0, h0, h0 / 2f);
        final Capa c = p.actual;
        if (c != null) {
            c.setAlpha(0f);
            desenfocar(c, 8f);
        }
        final float dur = ios ? 460f : EMER_W.durMs();
        final Muelle mu = new Muelle(5.6f, 1.25f);
        p.cancelarRect();
        p.rectAnim = tween(0, (long) (dur * ts()), null, new Paso() {
            @Override
            public void f(float t) {
                if (!p.vivo) return;
                float ms = t * dur;
                float u = ios ? mu.getInterpolation(t) : EMER_W.en(ms);
                float hf = ios ? u : EMER_H.en(Math.min(ms, EMER_H.durMs()));
                int w = Math.max(px(24), Math.round(w0 + (wT - w0) * u));
                int hh = Math.max(px(20), Math.round(h0 + (hT - h0) * hf));
                p.colocar(pillCx - w / 2, topP + (hT - hh) / 2, w, hh, hh / 2f);
                reubicarCirc();
                if (c != null) {
                    float a = limitar((ms - 90f) / 200f, 0f, 1f);
                    c.setAlpha(a);
                    desenfocar(c, 8f * (1f - a));
                }
            }
        }, new Fin() {
            @Override
            public void f(boolean cancelada) {
                if (c != null) {
                    c.setAlpha(1f);
                    desenfocar(c, 0f);
                }
                if (!cancelada && p.vivo) {
                    p.colocar(pillCx - wT / 2, topP, wT, hT, hT / 2f);
                    reubicarCirc();
                }
            }
        });
    }

    /** La pildora se retira hacia la camara (curva medida del video) y desaparece al llegar. */
    private void salirPildora(final Pieza p) {
        p.vivo = false;
        p.cancelarRect();
        cancelarVuelta(p);
        if (p.clase == 3) {
            tween(0, (long) (140 * ts()), new android.view.animation.AccelerateInterpolator(),
                    new Paso() {
                        @Override
                        public void f(float t) {
                            float s = 1f - 0.35f * t;
                            p.capa.setScaleX(s);
                            p.capa.setScaleY(s);
                            p.capa.setAlpha(1f - t);
                            desenfocar(p.capa, 10f * t);
                        }
                    }, new Fin() {
                        @Override
                        public void f(boolean c) {
                            quitarPieza(p);
                        }
                    });
            return;
        }
        final int w0 = p.w;
        final int h0 = p.h;
        final int cx = p.x + p.w / 2;
        final int cy = p.y + p.h / 2;
        final int wHueco = Math.min(w0, px(26));
        final Capa c = p.actual;
        final float dur = ios ? 380f : SAL_W.durMs();
        final float a0 = p.capa.getAlpha();
        p.vuelta = null;
        tween(0, (long) (dur * ts()), null, new Paso() {
            @Override
            public void f(float t) {
                float ms = t * dur;
                float v = ios ? (1f - t) * (1f - t) : limitar((SAL_W.en(ms) - 0.17f) / 0.83f, 0f, 1f);
                float hf = ios ? 1f - 0.46f * t : SAL_H.en(Math.min(ms, SAL_H.durMs()));
                int w = Math.max(px(20), Math.round(wHueco + (w0 - wHueco) * v));
                int hh = Math.max(px(16), Math.round(h0 * hf));
                p.colocar(cx - w / 2, cy - hh / 2, w, hh, hh / 2f);
                if (c != null) {
                    float a = 1f - limitar(ms / 120f, 0f, 1f);
                    c.setAlpha(a);
                }
                float fin = limitar((ms - (dur - 90f)) / 90f, 0f, 1f);
                p.capa.setAlpha(a0 * (1f - fin));
            }
        }, new Fin() {
            @Override
            public void f(boolean cancelada) {
                quitarPieza(p);
            }
        });
    }

    private void cambiarPildora(final Pieza p, int antes, int ahora, Evento ev, int dW) {
        Capa nueva = contenidoPildora(ahora, ev, dW, hP);
        p.objW = dW;
        p.cancelarRect();
        float t = ts();
        if (ahora == 2 && antes != 2) {
            p.cambiarCapa(nueva, dW, hP, true, (long) (100 * t), (long) (170 * t));
            if (ios) {
                morfarMuelle(p, dW, 560);
            } else {
                morfar(p, p.w, dW, BAN_ANCHO_IN, BAN_ALTO_IN, false);
            }
        } else if (antes == 2 && ahora != 2) {
            p.cambiarCapa(nueva, dW, hP, true, (long) (170 * t), (long) (140 * t));
            if (ios) {
                morfarMuelle(p, dW, 380);
            } else {
                morfar(p, p.w, dW, BAN_ANCHO_OUT, BAN_ALTO_OUT, true);
            }
        } else {
            p.cambiarCapa(nueva, dW, hP, true, 40, (long) (150 * t));
            if (p.w != dW) {
                if (ios) {
                    morfarMuelle(p, dW, 420);
                } else {
                    anchoSuave(p, dW);
                }
            }
        }
    }

    /** Banner de Xiaomi: encoge un poco, se estira, se pasa y se asienta (curvas del video). */
    private void morfar(final Pieza p, final int w0, final int wT, final Curva cw,
                        final Curva chh, final boolean salida) {
        final float dur = cw.durMs();
        p.cancelarRect();
        p.rectAnim = tween(0, (long) (dur * ts()), null, new Paso() {
            @Override
            public void f(float t) {
                if (!p.vivo) return;
                float ms = t * dur;
                float u = cw.en(ms);
                float hf = chh.en(Math.min(ms, chh.durMs()));
                int w = salida ? Math.round(wT + (w0 - wT) * u) : Math.round(w0 + (wT - w0) * u);
                w = Math.max(px(36), w);
                int hh = Math.max(px(24), Math.round(hP * hf));
                p.colocar(pillCx - w / 2, topP + (hP - hh) / 2, w, hh, hh / 2f);
                reubicarCirc();
            }
        }, null);
    }

    private void morfarMuelle(final Pieza p, final int wT, long ms) {
        p.objW = wT;
        final int w0 = p.w;
        p.cancelarRect();
        p.rectAnim = tween(0, (long) (ms * ts()), new Muelle(5.6f, 1.25f), new Paso() {
            @Override
            public void f(float t) {
                if (!p.vivo) return;
                int w = Math.max(px(36), Math.round(w0 + (wT - w0) * t));
                p.colocar(pillCx - w / 2, topP, w, hP, hP / 2f);
                reubicarCirc();
            }
        }, null);
    }

    private void anchoSuave(final Pieza p, final int wT) {
        p.objW = wT;
        final int w0 = p.w;
        p.cancelarRect();
        p.rectAnim = tween(0, (long) (170 * ts()), new DecelerateInterpolator(1.8f), new Paso() {
            @Override
            public void f(float t) {
                if (!p.vivo) return;
                int w = Math.max(px(36), Math.round(w0 + (wT - w0) * t));
                p.colocar(pillCx - w / 2, topP, w, hP, hP / 2f);
                reubicarCirc();
            }
        }, null);
    }

    private void reubicarCirc() {
        if (pCirc == null || pMain == null || !pCirc.vivo) return;
        pCirc.colocar(pMain.x + pMain.w + gap, topP, hP, hP, hP / 2f);
    }

    private void sincronizarCirc(Evento[] fila) {
        Evento d = banner == null ? fila[1] : null;
        if (d == null) {
            if (pCirc != null) {
                Pieza p = pCirc;
                pCirc = null;
                salirPildora(p);
            }
            return;
        }
        int mw = fila[0] != null ? anchoItem(fila[0]) : idleW;
        int cx = pillCx + mw / 2 + gap;
        String sg = "c:" + d.tipo;
        if (pCirc == null) {
            Pieza p = nuevaPildora();
            pCirc = p;
            p.sig = sg;
            p.ev = d;
            p.clase = 3;
            p.colocar(cx, topP, hP, hP, hP / 2f);
            Capa c = contenidoCirculo(d);
            p.ponerCapa(c, hP, hP);
            p.actual = c;
            anadir(p);
            entradaCirculo(p);
            return;
        }
        if (!pCirc.sig.equals(sg)) {
            pCirc.sig = sg;
            pCirc.ev = d;
            pCirc.cambiarCapa(contenidoCirculo(d), hP, hP, true, 40, (long) (150 * ts()));
        } else if (pCirc.ev != d) {
            pCirc.ev = d;
            pCirc.cambiarCapa(contenidoCirculo(d), hP, hP, false, 0, 0);
        }
        if (pCirc.x != cx && pMain != null && pMain.w == mw) {
            final Pieza p = pCirc;
            final int x0 = p.x;
            final int xT = cx;
            tween(0, (long) (170 * ts()), new DecelerateInterpolator(1.8f), new Paso() {
                @Override
                public void f(float t) {
                    if (!p.vivo) return;
                    p.colocar(Math.round(x0 + (xT - x0) * t), topP, hP, hP, hP / 2f);
                }
            }, null);
        }
    }

    private void entradaCirculo(final Pieza p) {
        p.capa.setPivotX(bleed + p.w / 2f);
        p.capa.setPivotY(bleed + p.h / 2f);
        p.capa.setAlpha(0f);
        p.capa.setScaleX(0.5f);
        p.capa.setScaleY(0.5f);
        desenfocar(p.capa, 10f);
        tween((long) (130 * ts()), (long) (260 * ts()), new Muelle(5.2f, 1.1f), new Paso() {
            @Override
            public void f(float t) {
                float s = 0.5f + 0.5f * t;
                p.capa.setScaleX(s);
                p.capa.setScaleY(s);
                p.capa.setAlpha(Math.min(1f, t * 3f));
                desenfocar(p.capa, 10f * (1f - Math.min(1f, t * 1.6f)));
            }
        }, new Fin() {
            @Override
            public void f(boolean c) {
                p.capa.setScaleX(1f);
                p.capa.setScaleY(1f);
                p.capa.setAlpha(1f);
                desenfocar(p.capa, 0f);
            }
        });
    }

    // ------------------------------------------------------------------ tarjetas

    private String sigCard(Evento e) {
        return "k:" + e.tipo + ":" + (e.clave != null ? e.clave
                : String.valueOf(System.identityHashCode(e))) + (panelAcciones ? "A" : "")
                + (respondiendo ? "R" : "");
    }

    private void sincronizarCard() {
        if (cardEv == null) {
            if (pCard != null) {
                Pieza p = pCard;
                pCard = null;
                salirCard(p, false);
            }
            return;
        }
        String sg = sigCard(cardEv);
        if (pCard == null) {
            pCard = crearCard(cardEv, sg);
            entradaCard(pCard, 0);
            return;
        }
        if (pCard.sig.equals(sg)) {
            if (pCard.ev != cardEv) {
                pCard.ev = cardEv;
                int ch = pCard.h;
                Capa c = contenidoCard(cardEv, cardW, ch);
                pCard.cambiarCapa(c, cardW, ch, false, 0, 0);
            }
            return;
        }
        Pieza vieja = pCard;
        pCard = crearCard(cardEv, sg);
        salirCard(vieja, true);
        entradaCard(pCard, (long) (40 * ts()));
    }

    private Pieza crearCard(Evento e, String sg) {
        Pieza p = nuevaCard(e);
        p.sig = sg;
        p.ev = e;
        p.clase = 4;
        int ch = alturaCard(e);
        p.colocar(cardX, cardTop, cardW, ch, radioCard(ch));
        Capa c = contenidoCard(e, cardW, ch);
        p.ponerCapa(c, cardW, ch);
        p.actual = c;
        inner.addView(p.capa, 0);
        return p;
    }

    private void entradaCard(final Pieza p, long retraso) {
        final int de = dirEntrada;
        dirEntrada = 0;
        if (ios) {
            final int w0 = pMain != null ? pMain.w : idleW;
            final int x1 = p.x;
            final int y1 = p.y;
            final int w1 = p.w;
            final int h1 = p.h;
            final float r1 = p.r;
            p.colocar(pillCx - w0 / 2, topP, w0, hP, hP / 2f);
            if (p.actual != null) {
                p.actual.setAlpha(0f);
                desenfocar(p.actual, 14f);
            }
            final Capa c = p.actual;
            tween(retraso + (long) (110 * ts()), (long) (240 * ts()), null, new Paso() {
                @Override
                public void f(float t) {
                    if (c == null) return;
                    c.setAlpha(t);
                    desenfocar(c, 14f * (1f - t));
                }
            }, new Fin() {
                @Override
                public void f(boolean cancel) {
                    if (c != null) {
                        c.setAlpha(1f);
                        desenfocar(c, 0f);
                    }
                }
            });
            final int xa = pillCx - w0 / 2;
            final float ex = de == 2 ? dp(40) : (de == 3 ? -dp(40) : 0f);
            p.cancelarRect();
            p.rectAnim = tween(retraso, (long) (580 * ts()), new Muelle(5.6f, 1.25f), new Paso() {
                @Override
                public void f(float t) {
                    if (!p.vivo) return;
                    int w = Math.max(px(40), Math.round(w0 + (w1 - w0) * t));
                    int hh = Math.max(px(30), Math.round(hP + (h1 - hP) * t));
                    int xx = Math.round(xa + (x1 - xa) * t);
                    int yy = Math.round(topP + (y1 - topP) * t);
                    float rr = hP / 2f + (r1 - hP / 2f) * Math.min(1f, t);
                    p.colocar(xx, yy, w, hh, Math.min(rr, hh / 2f));
                    if (ex != 0f) p.capa.setTranslationX(ex * (1f - Math.min(1f, t)));
                }
            }, new Fin() {
                @Override
                public void f(boolean cancel) {
                    if (ex != 0f && p.vivo) p.capa.setTranslationX(0f);
                }
            });
            return;
        }
        // Xiaomi: aparece desde arriba con escala 0.78, desenfoque 16 y alfa 0.5 (medido del video).
        // Al navegar entre tarjetas llega ademas deslizandose desde el lado contrario.
        final float ex = de == 2 ? dp(54) : (de == 3 ? -dp(54) : 0f);
        final float s0 = ex != 0f ? 0.92f : 0.78f;
        p.capa.setPivotX(bleed + p.w / 2f);
        p.capa.setPivotY(bleed);
        p.capa.setScaleX(s0);
        p.capa.setScaleY(s0);
        p.capa.setAlpha(0.5f);
        p.capa.setTranslationX(ex);
        desenfocar(p.capa, 16f);
        tween(retraso, (long) ((ex != 0f ? 260 : 210) * ts()), null, new Paso() {
            @Override
            public void f(float t) {
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                float s = s0 + (1f - s0) * e;
                p.capa.setScaleX(s);
                p.capa.setScaleY(s);
                p.capa.setTranslationX(ex * (1f - e));
                p.capa.setAlpha(Math.min(1f, 0.5f + t * 1.1f));
                desenfocar(p.capa, 16f * (1f - e));
            }
        }, new Fin() {
            @Override
            public void f(boolean c) {
                p.capa.setScaleX(1f);
                p.capa.setScaleY(1f);
                p.capa.setTranslationX(0f);
                p.capa.setAlpha(1f);
                desenfocar(p.capa, 0f);
            }
        });
    }

    /**
     * La tarjeta sale continuando desde donde este (arrastrada, inclinada, a medio desvanecer):
     * nada salta al soltar. Si se fue deslizando, sigue en esa direccion mientras se apaga.
     */
    private void salirCard(final Pieza p, boolean cambio) {
        p.vivo = false;
        p.cancelarRect();
        cancelarVuelta(p);
        piezasSalientes.add(p);
        final int dir = dirSalida;
        dirSalida = 0;
        final View c0 = p.capa;
        c0.animate().cancel();
        final float tx0 = c0.getTranslationX();
        final float ty0 = c0.getTranslationY();
        final float sx0 = c0.getScaleX();
        final float sy0 = c0.getScaleY();
        final float a0 = c0.getAlpha();
        final float rx0 = c0.getRotationX();
        final float ry0 = c0.getRotationY();
        final float b0 = p.blur;
        if (ios && !cambio) {
            final int x0 = p.x;
            final int y0 = p.y;
            final int w0 = p.w;
            final int h0 = p.h;
            final int w1 = pMain != null ? pMain.w : idleW;
            final int x1 = pillCx - w1 / 2;
            if (p.actual != null) {
                final Capa c = p.actual;
                tween(0, (long) (110 * ts()), null, new Paso() {
                    @Override
                    public void f(float t) {
                        c.setAlpha(1f - t);
                        desenfocar(c, 12f * t);
                    }
                }, null);
            }
            tween(0, (long) (330 * ts()), new DecelerateInterpolator(1.6f), new Paso() {
                @Override
                public void f(float t) {
                    int w = Math.max(px(40), Math.round(w0 + (w1 - w0) * t));
                    int hh = Math.max(px(30), Math.round(h0 + (hP - h0) * t));
                    int xx = Math.round(x0 + (x1 - x0) * t);
                    int yy = Math.round(y0 + (topP - y0) * t);
                    p.colocar(xx, yy, w, hh, Math.min(hh / 2f, radioCard(h0)
                            + (hP / 2f - radioCard(h0)) * t));
                    p.capa.setTranslationX(tx0 * (1f - t));
                    p.capa.setTranslationY(ty0 * (1f - t));
                    p.capa.setScaleX(sx0 + (1f - sx0) * t);
                    p.capa.setScaleY(sy0 + (1f - sy0) * t);
                    p.capa.setRotationX(rx0 * (1f - t));
                    p.capa.setRotationY(ry0 * (1f - t));
                    p.capa.setAlpha(a0 * (t > 0.8f ? (1f - t) * 5f : 1f));
                }
            }, new Fin() {
                @Override
                public void f(boolean c) {
                    piezasSalientes.remove(p);
                    quitarPieza(p);
                }
            });
            return;
        }
        if (sx0 >= 0.999f && sy0 >= 0.999f) {
            p.capa.setPivotX(bleed + p.w / 2f);
            p.capa.setPivotY(bleed);
        }
        final boolean desliza = dir != 0;
        final float fin = desliza ? 0.9f : (cambio ? 0.88f : 0.82f);
        final float txFin = dir == 2 ? tx0 - dp(60) : (dir == 3 ? tx0 + dp(60) : tx0);
        final float tyFin = dir == 1 ? ty0 - dp(26) : (dir == 4 ? ty0 + dp(34) : ty0);
        final long dur = (long) ((desliza ? 200 : (cambio ? 110 : 160)) * ts());
        tween(0, dur,
                desliza ? new DecelerateInterpolator(1.3f)
                        : new android.view.animation.AccelerateInterpolator(),
                new Paso() {
                    @Override
                    public void f(float t) {
                        float sa = Math.min(1f, t * (desliza ? 1.25f : 1f));
                        p.capa.setScaleX(sx0 + (fin - sx0) * t);
                        p.capa.setScaleY(sy0 + (fin - sy0) * t);
                        p.capa.setTranslationX(tx0 + (txFin - tx0) * t);
                        p.capa.setTranslationY(ty0 + (tyFin - ty0) * t);
                        p.capa.setRotationX(rx0 * (1f - t));
                        p.capa.setRotationY(ry0 * (1f - t));
                        p.capa.setAlpha(a0 * (1f - sa));
                        desenfocar(p.capa, b0 + (14f - b0) * t);
                    }
                }, new Fin() {
                    @Override
                    public void f(boolean c) {
                        piezasSalientes.remove(p);
                        quitarPieza(p);
                    }
                });
    }

    // ------------------------------------------------------------------ reloj

    private boolean relojActivo;
    private final Runnable reloj = new Runnable() {
        @Override
        public void run() {
            relojActivo = false;
            if (root == null) return;
            try {
                ocultarEnPantallaCompleta();
                tick(pMain);
                tick(pCirc);
                tick(pCard);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            iniciarReloj();
        }
    };

    private boolean ocultoFs;

    /** Con "ocultar en pantalla completa" la isla se desvanece (salvo llamadas) y vuelve sola. */
    private void ocultarEnPantallaCompleta() {
        if (root == null) return;
        boolean ocultar = cfg.ocultarFs && llamada == null && pantallaCompleta();
        if (ocultar == ocultoFs) return;
        ocultoFs = ocultar;
        root.animate().cancel();
        root.animate().alpha(ocultar ? 0f : 1f).setDuration(220).start();
    }

    private void tick(Pieza p) {
        if (p == null || p.actual == null) return;
        for (Runnable r : p.actual.ticks) r.run();
    }

    private void iniciarReloj() {
        if (relojActivo || root == null) return;
        relojActivo = true;
        h.postDelayed(reloj, 1000 - (SystemClock.elapsedRealtime() % 1000) + 5);
    }

    private long msReloj(Evento e) {
        long ahora = SystemClock.elapsedRealtime();
        return e.cuentaAtras ? e.base - ahora : ahora - e.base;
    }

    private String formatear(long ms) {
        long s = Math.max(0, ms / 1000);
        long hh = s / 3600;
        long mm = (s % 3600) / 60;
        long ss = s % 60;
        return hh > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hh, mm, ss)
                : String.format(Locale.ROOT, "%02d:%02d", mm, ss);
    }

    private String textoReloj(Evento e, boolean medida) {
        if (e == null || e.base <= 0) return "";
        long ms = msReloj(e);
        if (medida) return ms >= 3600000L ? "0:00:00" : "00:00";
        return formatear(ms);
    }

    // ------------------------------------------------------------------ constructores de contenido

    private FrameLayout.LayoutParams lp(int w, int hh, int g, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, hh, g);
        p.leftMargin = l;
        p.topMargin = t;
        p.rightMargin = r;
        p.bottomMargin = b;
        return p;
    }

    private FrameLayout.LayoutParams abs(int w, int hh, int l, int t) {
        return lp(w, hh, Gravity.TOP | Gravity.START, l, t, 0, 0);
    }

    private TextView tv(CharSequence s, float tamPx, int color, boolean negrita) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, tamPx);
        t.setTextColor(color);
        t.setTypeface(negrita ? Typeface.create("sans-serif", Typeface.BOLD)
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int colorAcc(Evento e, int def) {
        if (e == null || e.color == 0xFFFFFFFF || Color.alpha(e.color) == 0) return def;
        return e.color;
    }

    private Drawable copiaIcono(Drawable d) {
        try {
            if (d != null && d.getConstantState() != null) {
                return d.getConstantState().newDrawable().mutate();
            }
        } catch (Throwable t) {
            // se usa el mismo
        }
        return d;
    }

    private View portada(Evento e, int tam, float radio, int tamGlifo) {
        final float rr = radio;
        FrameLayout f = new FrameLayout(ctx);
        f.setBackgroundColor(0xFF1C2230);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rr);
            }
        });
        f.setClipToOutline(true);
        if (e != null && e.icono != null) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copiaIcono(e.icono));
            f.addView(iv, new FrameLayout.LayoutParams(tam, tam));
        } else {
            GlyphView g = new GlyphView(ctx);
            g.set(e != null && e.glifo != 0 ? e.glifo : GlyphView.NOTA, 0f, false, 0,
                    colorAcc(e, 0xFFFFFFFF));
            f.addView(g, new FrameLayout.LayoutParams(tamGlifo, tamGlifo, Gravity.CENTER));
        }
        return f;
    }

    private GradientDrawable ovalo(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable pildoraFondo(int color, float radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radio);
        return g;
    }

    private void tickTexto(final Capa c, final TextView t, final Evento e) {
        c.ticks.add(new Runnable() {
            @Override
            public void run() {
                t.setText(textoReloj(e, false));
            }
        });
    }

    // ---- pildora (capsula principal y banners)

    private Capa contenidoPildora(int clase, Evento e, int w, int hh) {
        Capa c = new Capa();
        if (clase == 1 && e != null) {
            llenarItem(c, e);
        } else if (clase == 2 && e != null) {
            llenarBanner(c, e, w);
        }
        return c;
    }

    private void llenarItem(Capa c, Evento e) {
        switch (e.tipo) {
            case Evento.CRONO: {
                IconView ic = new IconView(ctx);
                ic.set(IconView.CRONO, colorAcc(e, 0xFF4984FB));
                ic.setGirando(e.base > 0);
                c.addView(ic, lp(px(26), px(26), Gravity.START | Gravity.CENTER_VERTICAL,
                        px(24), 0, 0, 0));
                if (e.base > 0) {
                    TextView t = tv(textoReloj(e, false), px(27), 0xFFFFFFFF, false);
                    c.addView(t, lp(FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, px(20), 0));
                    tickTexto(c, t, e);
                }
                break;
            }
            case Evento.LINTERNA: {
                IconView ic = new IconView(ctx);
                ic.set(IconView.LINTERNA_RAYOS, 0xFFFFFFFF);
                c.addView(ic, lp(px(30), px(30), Gravity.START | Gravity.CENTER_VERTICAL,
                        px(22), 0, 0, 0));
                break;
            }
            case Evento.MUSICA: {
                c.addView(portada(e, px(28), px(7), px(18)), lp(px(28), px(28),
                        Gravity.START | Gravity.CENTER_VERTICAL, px(23), 0, 0, 0));
                EqualizerView eq = new EqualizerView(ctx);
                eq.setColor(0xFFFFFFFF);
                eq.setAnimando(e.reproduciendo);
                c.addView(eq, lp(px(26), px(22), Gravity.END | Gravity.CENTER_VERTICAL, 0, 0,
                        px(20), 0));
                break;
            }
            case Evento.LLAMADA: {
                GlyphView g = new GlyphView(ctx);
                g.set(GlyphView.TELEFONO, 0f, false, 0, 0xFF34C759);
                c.addView(g, lp(px(28), px(28), Gravity.START | Gravity.CENTER_VERTICAL,
                        px(23), 0, 0, 0));
                if (e.sub == 2 && e.base > 0) {
                    TextView t = tv(textoReloj(e, false), px(27), 0xFF34C759, false);
                    c.addView(t, lp(FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, px(20), 0));
                    tickTexto(c, t, e);
                }
                break;
            }
            default:
                break;
        }
    }

    private String textoBanner(Evento e) {
        if (e.tipo == Evento.TIMBRE) {
            return e.sub == 0 ? "Modo silencio activado" : e.sub == 1
                    ? "Modo vibración activado" : "Modo timbre activado";
        }
        String s = String.valueOf(e.titulo == null ? "" : e.titulo);
        CharSequence t = e.texto;
        if (t != null && t.length() > 0 && e.tipo != Evento.NOTIF) s = s + " · " + t;
        if (e.tipo == Evento.NOTIF) {
            if (e.agrupadas > 1) {
                s = s + " · " + e.agrupadas;
            } else if (t != null && t.length() > 0) {
                s = s + ": " + t;
            }
        }
        return s;
    }

    private void llenarBanner(Capa c, Evento e, int w) {
        boolean pct = (e.tipo == Evento.CARGA || e.tipo == Evento.BATERIA
                || e.tipo == Evento.VOLUMEN) && e.nivel >= 0;
        int acc = e.tipo == Evento.TIMBRE && e.sub == 0 ? 0xFFFF453A : colorAcc(e, 0xFFFFFFFF);
        int mitad = w / 2 - cutW / 2 - px(12);
        if (pct) {
            TextView t = tv(String.valueOf(e.titulo), px(21), e.tipo == Evento.CARGA
                    ? 0xFFFFFFFF : acc, false);
            t.setEllipsize(null);
            t.setMaxWidth(Math.max(px(60), mitad - px(24)));
            c.addView(t, lp(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL,
                    px(26), 0, 0, 0));
            int gl = e.tipo == Evento.CARGA ? GlyphView.RAYO
                    : (e.tipo == Evento.BATERIA ? GlyphView.AHORRO
                    : (e.glifo != 0 ? e.glifo : GlyphView.VOLUMEN));
            if (e.tipo == Evento.CARGA) {
                View halo = new View(ctx);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
                g.setGradientRadius(px(22));
                g.setColors(new int[]{(acc & 0x00FFFFFF) | 0x88000000, acc & 0x00FFFFFF});
                halo.setBackground(g);
                c.addView(halo, lp(px(44), px(44), Gravity.END | Gravity.CENTER_VERTICAL, 0, 0,
                        px(10), 0));
            }
            GlyphView g = new GlyphView(ctx);
            g.set(gl, 0f, e.activo, e.sub, acc);
            c.addView(g, lp(px(28), px(28), Gravity.END | Gravity.CENTER_VERTICAL, 0, 0,
                    px(18), 0));
            TextView v = tv(e.nivel + "%", px(24), acc, true);
            c.addView(v, lp(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL,
                    0, 0, px(18) + px(28) + px(8), 0));
            return;
        }
        if (e.glifo != 0) {
            GlyphView g = new GlyphView(ctx);
            g.set(e.glifo, e.nivel < 0 ? 0f : e.nivel / 100f, e.activo, e.sub, acc);
            c.addView(g, lp(px(30), px(30), Gravity.START | Gravity.CENTER_VERTICAL, px(24), 0,
                    0, 0));
        } else if (e.icono != null) {
            c.addView(portada(e, px(30), px(8), px(18)), lp(px(30), px(30),
                    Gravity.START | Gravity.CENTER_VERTICAL, px(24), 0, 0, 0));
        }
        int x0 = w / 2 + cutW / 2 + px(14);
        TextView t = tv(textoBanner(e), px(22), acc, true);
        t.setEllipsize(null);
        c.addView(t, lp(Math.max(px(40), w - x0 - px(8)), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL, x0, 0, 0, 0));
    }

    // ---- circulo

    private Capa contenidoCirculo(Evento e) {
        Capa c = new Capa();
        switch (e.tipo) {
            case Evento.CRONO: {
                IconView ic = new IconView(ctx);
                ic.set(IconView.CRONO, colorAcc(e, 0xFF4984FB));
                ic.setGirando(e.base > 0);
                c.addView(ic, lp(px(30), px(30), Gravity.CENTER, 0, 0, 0, 0));
                break;
            }
            case Evento.LINTERNA: {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
                g.setGradientRadius(hP / 2f);
                g.setColors(new int[]{0xFF4A4A4C, 0xFF050505});
                c.setBackground(g);
                IconView ic = new IconView(ctx);
                ic.set(IconView.LINTERNA_RAYOS, 0xFFFFFFFF);
                c.addView(ic, lp(px(36), px(36), Gravity.CENTER, 0, 0, 0, 0));
                break;
            }
            case Evento.MUSICA:
                c.addView(portada(e, px(38), px(8), px(22)), lp(px(38), px(38), Gravity.CENTER,
                        0, 0, 0, 0));
                break;
            case Evento.LLAMADA: {
                GlyphView g = new GlyphView(ctx);
                g.set(GlyphView.TELEFONO, 0f, false, 0, 0xFF34C759);
                c.addView(g, lp(px(32), px(32), Gravity.CENTER, 0, 0, 0, 0));
                break;
            }
            default:
                break;
        }
        return c;
    }

    // ---- tarjetas

    private Capa contenidoCard(Evento e, int w, int hh) {
        Capa c = new Capa();
        if (respondiendo && !cardVivo) {
            cardRespuesta(c, e, w, hh);
        } else if (panelAcciones && !cardVivo) {
            cardAcciones(c, e, w, hh);
        } else {
            switch (e.tipo) {
                case Evento.CRONO:
                    cardCrono(c, e, w, hh);
                    break;
                case Evento.LINTERNA:
                    cardLinterna(c, e, w, hh);
                    break;
                case Evento.MUSICA:
                    cardMusica(c, e, w, hh);
                    break;
                case Evento.LLAMADA:
                    cardLlamada(c, e, w, hh);
                    break;
                default:
                    cardNotif(c, e, w, hh);
                    break;
            }
        }
        return c;
    }

    private int iconoBoton(String titulo) {
        String t = titulo == null ? "" : titulo.toLowerCase(Locale.ROOT);
        if (t.contains("paus")) return IconView.PAUSA;
        if (t.contains("reanud") || t.contains("resum") || t.contains("inici") || t.contains("start")
                || t.contains("play") || t.contains("contin")) {
            return IconView.PLAY;
        }
        if (t.contains("vuelta") || t.contains("lap") || t.contains("marca") || t.contains("parcial")
                || t.contains("split")) {
            return IconView.BANDERA;
        }
        if (t.contains("reinic") || t.contains("reset") || t.contains("restab")) {
            return IconView.REINICIAR;
        }
        if (t.contains("detener") || t.contains("stop") || t.contains("termin")
                || t.contains("finaliz") || t.contains("cancel") || t.contains("descart")
                || t.contains("dismiss")) {
            return IconView.PARAR;
        }
        if (t.contains("más") || t.contains("mas ") || t.contains("add") || t.contains("+")) {
            return IconView.MAS;
        }
        return 0;
    }

    private View botonRedondo(final Evento e, final int i, int d, boolean cierraAlPulsar) {
        String t = String.valueOf(e.botonTxt[i]).toLowerCase(Locale.ROOT);
        boolean cuelga = t.contains("rech") || t.contains("colg") || t.contains("decl")
                || t.contains("hang") || t.contains("end") || t.contains("reject");
        boolean contesta = t.contains("contest") || t.contains("answer") || t.contains("acept")
                || t.contains("accept") || t.contains("atend");
        FrameLayout f = new FrameLayout(ctx);
        int fill = 0x1CFFFFFF;
        View icono;
        if (cuelga || contesta) {
            fill = cuelga ? 0xFFFF453A : 0xFF34C759;
            GlyphView g = new GlyphView(ctx);
            g.set(cuelga ? GlyphView.COLGAR : GlyphView.TELEFONO, 0f, false, 0, 0xFFFFFFFF);
            icono = g;
        } else {
            int ic = iconoBoton(String.valueOf(e.botonTxt[i]));
            if (ic != 0) {
                IconView iv = new IconView(ctx);
                iv.set(ic, 0xFFFFFFFF);
                icono = iv;
            } else {
                TextView tx = tv(String.valueOf(e.botonTxt[i]), d * 0.2f, 0xFFFFFFFF, true);
                tx.setGravity(Gravity.CENTER);
                icono = tx;
            }
        }
        f.setBackground(ovalo(fill));
        int ti = Math.round(d * 0.46f);
        f.addView(icono, icono instanceof TextView
                ? new FrameLayout.LayoutParams(d - px(10), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER)
                : new FrameLayout.LayoutParams(ti, ti, Gravity.CENTER));
        final boolean cierra = cierraAlPulsar;
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (e.botonRun != null && i < e.botonRun.length && e.botonRun[i] != null) {
                        e.botonRun[i].run();
                    }
                } catch (Throwable t2) {
                    XposedBridge.log(t2);
                }
                if (cierra) {
                    cerrarCard();
                } else {
                    reiniciarCard();
                }
            }
        });
        return f;
    }

    private void cardCrono(Capa c, Evento e, int w, int hh) {
        IconView ic = new IconView(ctx);
        ic.set(IconView.CRONO, colorAcc(e, 0xFF4984FB));
        ic.setGirando(e.base > 0);
        c.addView(ic, abs(px(88), px(88), px(32), px(22)));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(e.base > 0 ? textoReloj(e, false) : String.valueOf(e.titulo), px(42),
                0xFFFFFFFF, true);
        col.addView(t);
        TextView l = tv(String.valueOf(e.texto), px(24), 0xFF8E9094, false);
        l.setMaxWidth(px(250));
        l.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(l);
        c.addView(col, lp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL,
                px(144), px(4), 0, 0));
        if (e.base > 0) tickTexto(c, t, e);
        int n = e.botonTxt == null ? 0 : Math.min(2, e.botonTxt.length);
        for (int i = 0; i < n; i++) {
            int slot = n == 1 ? 1 : i;
            int left = slot == 0 ? px(430) : px(545);
            c.addView(botonRedondo(e, i, px(95), false), abs(px(95), px(95), left, px(30)));
        }
        handle(c, w, hh);
    }

    private void handle(Capa c, int w, int hh) {
        View hd = new View(ctx);
        hd.setBackground(pildoraFondo(0x33FFFFFF, px(3)));
        c.addView(hd, abs(px(110), px(6), w / 2 - px(55), px(135)));
    }

    private void cardLinterna(Capa c, final Evento e, int w, int hh) {
        IconView ic = new IconView(ctx);
        ic.set(IconView.LINTERNA, 0xFFFFFFFF);
        c.addView(ic, abs(px(87), px(87), px(31), px(36)));
        if (e.botonRun != null && e.botonRun.length > 1 && e.botonRun[1] != null) {
            ic.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        e.botonRun[1].run();
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                    reiniciarCard();
                }
            });
        }
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(String.valueOf(e.titulo), px(32), 0xFFFFFFFF, true));
        TextView s = tv(String.valueOf(e.texto), px(23), 0xFF8E9094, false);
        s.setMaxWidth(px(330));
        s.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(s);
        c.addView(col, lp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL,
                px(144), px(2), 0, 0));
        if (e.botonTxt != null && e.botonTxt.length > 0) {
            TextView b = tv(String.valueOf(e.botonTxt[0]), px(25), 0xFFFFFFFF, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(pildoraFondo(0x1CFFFFFF, px(30)));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        if (e.botonRun != null && e.botonRun.length > 0 && e.botonRun[0] != null) {
                            e.botonRun[0].run();
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                    cerrarCard();
                }
            });
            c.addView(b, lp(px(155), px(60), Gravity.END | Gravity.TOP, 0, px(47), px(28), 0));
        }
    }

    private String tiempoCorto(long ms) {
        return formatear(ms);
    }

    private void cardMusica(final Capa c, final Evento e, int w, int hh) {
        c.addView(portada(e, px(98), px(16), px(48)), abs(px(98), px(98), px(28), px(29)));
        TextView t = tv(String.valueOf(e.titulo), px(36), 0xFFFFFFFF, true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t, abs(px(400), FrameLayout.LayoutParams.WRAP_CONTENT, px(150), px(40)));
        TextView s = tv(String.valueOf(e.texto), px(24), 0xB3FFFFFF, false);
        s.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(s, abs(px(400), FrameLayout.LayoutParams.WRAP_CONTENT, px(150), px(94)));
        IconView cast = new IconView(ctx);
        cast.set(IconView.CAST, 0xFFFFFFFF);
        c.addView(cast, abs(px(44), px(44), px(568), px(54)));

        IconView ant = new IconView(ctx);
        ant.set(IconView.ANTERIOR, 0xFFFFFFFF);
        c.addView(ant, abs(px(56), px(56), px(176), px(168)));
        final IconView pl = new IconView(ctx);
        pl.set(e.reproduciendo ? IconView.PAUSA : IconView.PLAY, 0xFFFFFFFF);
        c.addView(pl, abs(px(56), px(56), px(307), px(168)));
        IconView sig = new IconView(ctx);
        sig.set(IconView.SIGUIENTE, 0xFFFFFFFF);
        c.addView(sig, abs(px(56), px(56), px(438), px(168)));
        ant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.anterior();
                reiniciarCard();
            }
        });
        pl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.alternar();
                reiniciarCard();
            }
        });
        sig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.siguiente();
                reiniciarCard();
            }
        });
        BarraMusica bm = new BarraMusica(ctx);
        bm.set(e);
        c.addView(bm, abs(px(366), px(28), px(152), px(247)));
        final TextView t1 = tv(tiempoCorto(e.posicionAhora()), px(22), 0xB3FFFFFF, false);
        c.addView(t1, abs(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, px(47), px(249)));
        final TextView t2 = tv(tiempoCorto(e.duracion), px(22), 0xB3FFFFFF, false);
        c.addView(t2, lp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END, 0, px(249),
                px(47), 0));
        c.ticks.add(new Runnable() {
            @Override
            public void run() {
                t1.setText(tiempoCorto(e.posicionAhora()));
                t2.setText(tiempoCorto(e.duracion));
            }
        });
        handle(c, w, hh);
        c.getChildAt(c.getChildCount() - 1).setLayoutParams(abs(px(110), px(6), w / 2 - px(55),
                px(288)));
    }

    private void cardLlamada(Capa c, Evento e, int w, int hh) {
        TextView n = tv(String.valueOf(e.titulo), px(36), 0xFFFFFFFF, true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(n, abs(px(380), FrameLayout.LayoutParams.WRAP_CONTENT, px(44), px(38)));
        if (e.sub == 2 && e.base > 0) {
            TextView t = tv(textoReloj(e, false), px(26), 0xFF34C759, false);
            c.addView(t, abs(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, px(44), px(94)));
            tickTexto(c, t, e);
        } else {
            TextView t = tv(String.valueOf(e.texto), px(25), 0xFF8E9094, false);
            t.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(t, abs(px(380), FrameLayout.LayoutParams.WRAP_CONTENT, px(44), px(94)));
        }
        int n2 = e.botonTxt == null ? 0 : Math.min(2, e.botonTxt.length);
        for (int i = 0; i < n2; i++) {
            int slot = n2 == 1 ? 1 : i;
            int left = slot == 0 ? px(430) : px(545);
            c.addView(botonRedondo(e, i, px(95), false), abs(px(95), px(95), left, px(30)));
        }
    }

    private void cardNotif(Capa c, final Evento e, int w, int hh) {
        boolean boton = e.respPi != null && e.respRi != null;
        boolean foto = e.imagen != null && !boton;
        int derecha = boton ? px(190) : (foto ? px(140) : px(28));
        View ic = portada(e, px(84), px(20), px(44));
        if (e.icono == null) {
            ic.setBackgroundColor(Color.argb(60, Color.red(colorAcc(e, 0xFF34C759)),
                    Color.green(colorAcc(e, 0xFF34C759)), Color.blue(colorAcc(e, 0xFF34C759))));
        }
        c.addView(ic, abs(px(84), px(84), px(28), px(35)));
        int ancho = Math.max(px(120), w - px(136) - derecha);
        if (e.app != null && e.app.length() > 0) {
            TextView a = tv(String.valueOf(e.app), px(20), 0x99FFFFFF, false);
            a.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(a, abs(ancho, FrameLayout.LayoutParams.WRAP_CONTENT, px(136), px(20)));
        }
        String tit = String.valueOf(e.titulo);
        if (e.agrupadas > 1) tit = tit + " · " + e.agrupadas + " mensajes";
        TextView t = tv(tit, px(30), 0xFFFFFFFF, true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t, abs(ancho, FrameLayout.LayoutParams.WRAP_CONTENT, px(136), px(46)));
        TextView s = tv(String.valueOf(e.texto), px(24), 0xFFB0B3B8, false);
        s.setSingleLine(false);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(s, abs(ancho, FrameLayout.LayoutParams.WRAP_CONTENT, px(136), px(88)));
        if (foto) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copiaIcono(e.imagen));
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(18));
                }
            });
            iv.setClipToOutline(true);
            c.addView(iv, lp(px(100), px(100), Gravity.END | Gravity.TOP, 0, px(27), px(28), 0));
        }
        if (boton) {
            TextView b = tv("Responder", px(24), 0xFFFFFFFF, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(pildoraFondo(0x1CFFFFFF, px(28)));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    iniciarRespuesta();
                }
            });
            c.addView(b, lp(px(150), px(56), Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, px(28),
                    0));
        }
    }

    private void cardAcciones(Capa c, final Evento e, int w, int hh) {
        String cab = e.app != null && e.app.length() > 0 ? String.valueOf(e.app)
                : String.valueOf(e.titulo);
        TextView a = tv(cab, px(24), 0xFF8E9094, false);
        a.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(a, abs(w - px(88), FrameLayout.LayoutParams.WRAP_CONTENT, px(44), px(22)));
        int n = e.botonTxt == null ? 0 : Math.min(4, e.botonTxt.length);
        if (n == 0) return;
        int gapB = px(14);
        int bw = (w - px(56) - (n - 1) * gapB) / n;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            TextView b = tv(String.valueOf(e.botonTxt[i]), px(23), 0xFFFFFFFF, true);
            b.setGravity(Gravity.CENTER);
            int col = e.botonColor != null && i < e.botonColor.length ? e.botonColor[i] : 0x33FFFFFF;
            b.setBackground(pildoraFondo(col, px(35)));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        if (e.botonRun != null && idx < e.botonRun.length
                                && e.botonRun[idx] != null) {
                            e.botonRun[idx].run();
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                    cerrarCard();
                }
            });
            c.addView(b, abs(bw, px(70), px(28) + i * (bw + gapB), px(62)));
        }
    }

    private void cardRespuesta(final Capa c, final Evento e, int w, int hh) {
        final EditText et = new EditText(ctx);
        et.setHint("Responder a " + e.titulo);
        et.setHintTextColor(0x99FFFFFF);
        et.setTextColor(0xFFFFFFFF);
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(26));
        et.setSingleLine(true);
        et.setBackground(pildoraFondo(0x1CFFFFFF, px(40)));
        et.setPadding(px(28), 0, px(28), 0);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        c.addView(et, abs(w - px(56) - px(96), px(80), px(28), px(37)));
        TextView env = tv("➤", px(34), 0xFFFFFFFF, true);
        env.setGravity(Gravity.CENTER);
        env.setBackground(ovalo(0xFF34C759));
        c.addView(env, lp(px(80), px(80), Gravity.END | Gravity.TOP, 0, px(37), px(28), 0));
        env.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enviarRespuesta(e, et.getText().toString());
            }
        });
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ke) {
                enviarRespuesta(e, et.getText().toString());
                return true;
            }
        });
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!respondiendo || et.getWindowToken() == null) return;
                    et.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, 0);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }, 320);
    }

    // ------------------------------------------------------------------ respuesta rapida

    private void iniciarRespuesta() {
        Evento e = cardEv;
        if (e == null || e.respPi == null || e.respRi == null || cardVivo || root == null) return;
        cancelar(cardPend);
        cardPend = null;
        respondiendo = true;
        try {
            wm.updateViewLayout(root, params());
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        aplicar();
    }

    private void enviarRespuesta(Evento e, String texto) {
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty()) {
            terminarRespuesta(false);
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent();
            android.os.Bundle b = new android.os.Bundle();
            for (android.app.RemoteInput ri : e.respRi) {
                b.putCharSequence(ri.getResultKey(), t);
            }
            android.app.RemoteInput.addResultsToIntent(e.respRi, i, b);
            e.respPi.send(ctx, 0, i);
        } catch (Throwable x) {
            XposedBridge.log(x);
        }
        terminarRespuesta(true);
    }

    private void finalizarRespuestaUI() {
        respondiendo = false;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && root != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        try {
            if (root != null) wm.updateViewLayout(root, params());
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void terminarRespuesta(boolean enviado) {
        finalizarRespuestaUI();
        if (enviado) {
            cerrarCard();
        } else {
            aplicar();
            programarCard(3500);
        }
    }

    // ------------------------------------------------------------------ gestos

    private float dX;
    private float dY;
    private int objetivo;
    private boolean moviendo;
    private boolean largoHecho;
    private long ultimoToque;
    private VelocityTracker vt;
    /** Eje del arrastre: 0 sin decidir, 1 vertical, 2 horizontal. */
    private int eje;
    /** El arrastre ya paso el umbral de confirmacion (se avisa con una vibracion corta). */
    private boolean listo;
    /** Hay un dedo sobre la isla: la ventana se mantiene ancha para no recortar el arrastre. */
    private boolean arrastrando;
    /** Direccion con la que se va una tarjeta al soltarla: 1 arriba, 2 izq, 3 der, 4 abajo. */
    private int dirSalida;
    /** Direccion de la que llega la tarjeta siguiente al navegar. */
    private int dirEntrada;
    private final Runnable largoRun = new Runnable() {
        @Override
        public void run() {
            if (!moviendo && !largoHecho && objetivo != 0) {
                largoHecho = true;
                try {
                    soltarInclinacion(piezaDe(objetivo));
                    largo(objetivo);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }
    };

    private boolean dentro(Pieza p, float x, float y, int m) {
        return x >= p.x - m && x <= p.x + p.w + m && y >= p.y - m && y <= p.y + p.h + m;
    }

    private int hit(float x, float y) {
        int m = px(8);
        if (pCard != null && pCard.vivo && dentro(pCard, x, y, m)) return 3;
        if (pMain != null && pMain.vivo && dentro(pMain, x, y, m)) return 1;
        if (pCirc != null && pCirc.vivo && dentro(pCirc, x, y, m)) return 2;
        return 0;
    }

    private Pieza piezaDe(int o) {
        return o == 1 ? pMain : (o == 2 ? pCirc : pCard);
    }

    private static float tanh(float x) {
        return (float) Math.tanh(x);
    }

    private static float limitar(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    /** Seguimiento del dedo en el eje cruzado: acotado, como el de la isla de Xiaomi. */
    private float sigue(float d) {
        float l = dp(16);
        return l * tanh(d * 0.2f / l);
    }

    private void ponerBlur(Pieza p, float px) {
        float q = Math.round(px);
        if (q == p.blur) return;
        p.blur = q;
        desenfocar(p.capa, q);
    }

    private void cancelarVuelta(Pieza p) {
        if (p != null && p.vuelta != null) {
            p.vuelta.cancel();
            p.vuelta = null;
        }
    }

    private boolean toque(MotionEvent ev) {
        float rx = ev.getRawX();
        float ry = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                objetivo = hit(rx, ry);
                if (objetivo == 0) return false;
                dX = rx;
                dY = ry;
                moviendo = false;
                largoHecho = false;
                eje = 0;
                listo = false;
                dirSalida = 0;
                dirEntrada = 0;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                velocidad(ev);
                cancelar(cardPend);
                cancelar(bannerPend);
                h.postDelayed(largoRun, 450);
                Pieza p = piezaDe(objetivo);
                if (p != null && (p.clase != 0 || p.ev != null)) {
                    arrastrando = true;
                    ajustarVentana();
                    pulsar(p, rx, ry);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (objetivo == 0) return false;
                velocidad(ev);
                float dx = rx - dX;
                float dy = ry - dY;
                Pieza p = piezaDe(objetivo);
                if (!moviendo && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                    moviendo = true;
                    h.removeCallbacks(largoRun);
                    eje = Math.abs(dx) > Math.abs(dy) ? 2 : 1;
                    if (p != null) {
                        p.capa.animate().cancel();
                        soltarInclinacion(p);
                    }
                }
                if (moviendo && !largoHecho && p != null && (p.clase != 0 || p.ev != null)) {
                    arrastrar(p, objetivo, dx, dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (objetivo == 0) return false;
                h.removeCallbacks(largoRun);
                velocidad(ev);
                float vx = 0f;
                float vy = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                }
                float dx = rx - dX;
                float dy = ry - dY;
                int obj = objetivo;
                Pieza p = piezaDe(obj);
                try {
                    soltarGesto(obj, p, dx, dy, vx, vy);
                    // Lo que no se haya ido ni este ya volviendo se asienta sin dejar escala ni
                    // inclinacion a medias; los temporizadores de cierre vuelven a contar.
                    if (p != null && p.vivo && p.vuelta == null) volverFisico(p, 0f, 0f);
                    reanudar();
                } finally {
                    dirSalida = 0;
                    dirEntrada = 0;
                    finGesto();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                h.removeCallbacks(largoRun);
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                Pieza p = piezaDe(objetivo);
                volverFisico(p, 0f, 0f);
                reanudar();
                finGesto();
                return true;
            }
            default:
                return false;
        }
    }

    /** La velocidad se mide en coordenadas de pantalla: la ventana cambia de tamano al tocar. */
    private void velocidad(MotionEvent ev) {
        if (vt == null) return;
        MotionEvent c = MotionEvent.obtain(ev);
        c.setLocation(ev.getRawX(), ev.getRawY());
        vt.addMovement(c);
        c.recycle();
    }

    private void finGesto() {
        arrastrando = false;
        moviendo = false;
        objetivo = 0;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!arrastrando) ajustarVentana();
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }, 120);
    }

    /** Decide al soltar: confirma o cancela el gesto, contando la velocidad del dedo. */
    private void soltarGesto(int obj, Pieza p, float dx, float dy, float vx, float vy) {
        if (largoHecho) {
            volverFisico(p, 0f, 0f);
            reanudar();
            return;
        }
        boolean card = obj == 3;
        if (!moviendo) {
            // toque simple
            if (!card) volverFisico(p, 0f, 0f);
            tocar(obj);
            return;
        }
        // 80 ms de inercia: un golpe rapido corto tambien cuenta
        float pdx = dx + vx * 0.08f;
        float pdy = dy + vy * 0.08f;
        float umH = card ? dp(80) : dp(52);
        float umU = card ? umbralArriba(p) : dp(34);
        if (eje == 2 && Math.abs(pdx) > umH) {
            dirSalida = pdx < 0 ? 2 : 3;
            horizontal(obj, pdx, vx);
        } else if (eje == 1 && pdy < -umU) {
            dirSalida = 1;
            arriba(obj, vy);
        } else if (eje == 1 && pdy > (card ? dp(52) : dp(24))) {
            if (card) {
                dirSalida = 4;
                abajoCard();
            } else {
                volverFisico(p, 0f, 0f);
                tocar(obj);
            }
        } else {
            volverFisico(p, vx, vy);
            reanudar();
        }
    }

    private float umbralArriba(Pieza p) {
        return Math.max(dp(52), (p != null ? p.h : 0) * 0.55f);
    }

    /** Pulsar: la pieza se hunde un poco y la tarjeta se inclina hacia el lado pulsado. */
    private void pulsar(Pieza p, float rx, float ry) {
        cancelarVuelta(p);
        float esc = ios ? 0.965f : 0.97f;
        p.capa.animate().cancel();
        p.capa.animate().scaleX(esc).scaleY(esc).setDuration(110)
                .setInterpolator(new DecelerateInterpolator()).start();
        if (p == pCard && p.w > 0 && p.h > 0) {
            // zona central sin inclinacion y ganancia suave hacia los bordes (como Xiaomi)
            float nx = limitar((rx - (p.x + p.w / 2f)) / (p.w / 2f), -1f, 1f);
            float ny = limitar((ry - (p.y + p.h / 2f)) / (p.h / 2f), -1f, 1f);
            float ax = suave(nx);
            float ay = suave(ny);
            p.capa.setCameraDistance(1600f * density);
            p.capa.animate().rotationY(1.8f * ax).rotationX(-1.8f * ay).setDuration(120)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private static float suave(float v) {
        float a = limitar((Math.abs(v) - 0.20f) / 0.80f, 0f, 1f);
        return (v < 0f ? -1f : 1f) * (1f - (1f - a) * (1f - a));
    }

    private void soltarInclinacion(Pieza p) {
        if (p == null) return;
        if (p.capa.getRotationX() != 0f || p.capa.getRotationY() != 0f) {
            p.capa.animate().rotationX(0f).rotationY(0f).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /**
     * La pieza sigue al dedo. Al deslizar para quitarla se va desvaneciendo y desenfocando
     * (alfa 1 a 0.2 como la isla de Xiaomi), con friccion al tirar hacia abajo.
     */
    private void arrastrar(Pieza p, int obj, float dx, float dy) {
        boolean card = obj == 3;
        float base = ios ? 0.965f : 0.97f;
        float ease = Math.min(1f, Math.max(Math.abs(dx), Math.abs(dy)) / dp(28));
        float sb = base + (1f - base) * ease;
        float tx;
        float ty;
        float pr;
        float sx = sb;
        float sy = sb;
        float al = 1f;
        float bl = 0f;
        float maxBl = ios ? 0f : (card ? 12f : 5f);
        float minAl = ios ? 0.45f : 0.2f;
        if (eje == 2) {
            float um = card ? dp(80) : dp(52);
            float lim = card ? dp(170) : dp(110);
            pr = Math.min(1f, Math.abs(dx) / um);
            tx = lim * tanh(dx / lim);
            ty = sigue(dy);
            al = 1f - (1f - minAl) * pr;
            bl = maxBl * pr;
            sx = sb * (1f - 0.08f * pr);
            sy = sb * (1f - 0.015f * pr);
            confirmar(pr >= 1f);
        } else if (dy < 0f) {
            float um = card ? umbralArriba(p) : dp(34);
            pr = Math.min(1f, -dy / um);
            float lim = um * 1.6f;
            ty = -lim * tanh(-dy / lim);
            tx = sigue(dx);
            al = 1f - (1f - minAl) * pr;
            bl = maxBl * pr;
            float k2 = 1f - (ios ? 0.03f : 0.06f) * pr;
            sx = sb * k2;
            sy = sb * k2;
            confirmar(pr >= 1f);
        } else {
            float lim = card ? dp(64) : dp(16);
            ty = lim * tanh(dy / lim);
            tx = sigue(dx);
            pr = Math.min(1f, dy / (card ? dp(52) : dp(24)));
            if (card) al = 1f - 0.3f * pr;
            confirmar(card && pr >= 1f);
        }
        p.capa.setTranslationX(tx);
        p.capa.setTranslationY(ty);
        p.capa.setScaleX(sx);
        p.capa.setScaleY(sy);
        p.capa.setAlpha(al);
        ponerBlur(p, bl);
    }

    private void confirmar(boolean ahora) {
        if (ahora == listo) return;
        listo = ahora;
        if (cfg.haptic) vibrar(false);
    }

    /**
     * Vuelve la pieza a su sitio con un muelle fisico que arranca con la velocidad del dedo:
     * rebote suave en posicion y escala, sin rebote en alfa y desenfoque.
     */
    private void volverFisico(final Pieza p, float vx, float vy) {
        if (p == null || !p.vivo) return;
        cancelarVuelta(p);
        final View c = p.capa;
        c.animate().cancel();
        final float tx0 = c.getTranslationX();
        final float ty0 = c.getTranslationY();
        final float sx0 = c.getScaleX();
        final float sy0 = c.getScaleY();
        final float a0 = c.getAlpha();
        final float rx0 = c.getRotationX();
        final float ry0 = c.getRotationY();
        final float b0 = p.blur;
        if (Math.abs(tx0) < 0.5f && Math.abs(ty0) < 0.5f && Math.abs(sx0 - 1f) < 0.002f
                && Math.abs(sy0 - 1f) < 0.002f && a0 > 0.995f && Math.abs(rx0) < 0.05f
                && Math.abs(ry0) < 0.05f && b0 < 0.5f) {
            return;
        }
        final float vxx = limitar(vx, -dp(2600), dp(2600));
        final float vyy = limitar(vy, -dp(2600), dp(2600));
        final Resorte mv = new Resorte(0.36f, 0.66f);
        final Resorte es = new Resorte(0.30f, 0.58f);
        final Resorte fd = new Resorte(0.20f, 1f);
        p.vuelta = tween(0, 700, null, new Paso() {
            @Override
            public void f(float u) {
                float t = u * 0.7f;
                c.setTranslationX(mv.pos(tx0, vxx, t));
                c.setTranslationY(mv.pos(ty0, vyy, t));
                c.setScaleX(1f + es.pos(sx0 - 1f, 0f, t));
                c.setScaleY(1f + es.pos(sy0 - 1f, 0f, t));
                c.setRotationX(es.pos(rx0, 0f, t));
                c.setRotationY(es.pos(ry0, 0f, t));
                c.setAlpha(limitar(1f + fd.pos(a0 - 1f, 0f, t), 0f, 1f));
                ponerBlur(p, Math.max(0f, fd.pos(b0, 0f, t)));
            }
        }, new Fin() {
            @Override
            public void f(boolean cancelada) {
                if (cancelada) return;
                p.vuelta = null;
                c.setTranslationX(0f);
                c.setTranslationY(0f);
                c.setScaleX(1f);
                c.setScaleY(1f);
                c.setRotationX(0f);
                c.setRotationY(0f);
                c.setAlpha(1f);
                ponerBlur(p, 0f);
            }
        });
    }

    /** Tras un gesto sin efecto, los temporizadores de cierre vuelven a contar. */
    private void reanudar() {
        if (cardEv != null && cardPend == null && !respondiendo) {
            programarCard(Math.max(3000, duracionCard(cardEv)));
        }
        if (banner != null && bannerPend == null) programarBanner(duracionBanner(banner));
    }

    private int msAbrir(Evento e) {
        if (e.tipo == Evento.LLAMADA && e.sub == 1) return 0;
        return e.tipo == Evento.MUSICA ? 8000 : 6000;
    }

    private void tocar(int obj) {
        if (obj == 3) {
            tocarCard();
            return;
        }
        Pieza p = piezaDe(obj);
        if (p == null) return;
        volverSinReanudar(p);
        if (obj == 1 && p.clase == 2) {
            Evento b = banner;
            if (b == null) return;
            if (b.tipo == Evento.NOTIF && b.minimizada) {
                b.minimizada = false;
                cardEv = b;
                cardVivo = false;
                trans = b;
                banner = null;
                cancelar(bannerPend);
                bannerPend = null;
                programarCard(Math.max(4000, duracionCard(b)));
                aplicar();
            } else {
                if (b.accion != null) {
                    try {
                        b.accion.send();
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
                if (trans == b) trans = null;
                banner = null;
                cancelar(bannerPend);
                bannerPend = null;
                aplicar();
            }
            return;
        }
        Evento ev = p.ev;
        if (ev == null || p.clase == 0) return;
        if (!enVivos(ev)) return;
        abrirCardVivo(ev, msAbrir(ev));
    }

    private void volverSinReanudar(Pieza p) {
        volverFisico(p, 0f, 0f);
    }

    private void tocarCard() {
        Evento e = cardEv;
        if (e == null || respondiendo) return;
        long ahora = SystemClock.elapsedRealtime();
        boolean doble = cfg.dobleToque && ahora - ultimoToque < 350;
        ultimoToque = ahora;
        PendingIntent pi = e.accion;
        cerrarCard();
        if (doble) return;
        if (pi != null) {
            try {
                pi.send();
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    /** Tirar hacia abajo de la barrita de una tarjeta: abre la app (como la mini ventana de Xiaomi). */
    private void abajoCard() {
        ultimoToque = 0L;
        tocarCard();
    }

    /** Oculta un elemento fijo al deslizarlo; sigue activo y vuelve al anunciarse de nuevo. */
    private boolean ocultarVivo(Evento ev) {
        if (ev.tipo == Evento.MUSICA) {
            musicaSilenciada = true;
            return true;
        }
        if (ev.tipo == Evento.CRONO || ev.tipo == Evento.LINTERNA) {
            ocultos |= 1 << ev.tipo;
            return true;
        }
        return false;
    }

    private void descartarBanner() {
        if (trans == banner) trans = null;
        banner = null;
        cancelar(bannerPend);
        bannerPend = null;
        aplicar();
    }

    private void arriba(int obj, float vy) {
        Pieza p = piezaDe(obj);
        if (obj == 3) {
            cerrarCard();
            return;
        }
        if (p == null) return;
        if (obj == 1 && p.clase == 2) {
            descartarBanner();
            volverFisico(p, 0f, vy);
            return;
        }
        Evento ev = p.ev;
        if (ev != null && ocultarVivo(ev)) {
            aplicar();
        }
        volverFisico(p, 0f, vy);
    }

    private void horizontal(int obj, float dx, float vx) {
        Pieza p = piezaDe(obj);
        if (obj == 3 && cardEv != null) {
            if (cardEv.tipo == Evento.MUSICA && cardEv.control != null) {
                if (dx < 0) {
                    cardEv.siguiente();
                } else {
                    cardEv.anterior();
                }
                volverFisico(p, vx, 0f);
            } else if (!cardVivo) {
                navegar(dx < 0 ? 1 : -1, vx);
            } else if (cardEv.tipo == Evento.LLAMADA && cardEv.sub == 1) {
                volverFisico(p, vx, 0f);
            } else {
                cerrarCard();
            }
            return;
        }
        if (p == null) return;
        if (obj == 1 && p.clase == 2) {
            descartarBanner();
            volverFisico(p, vx, 0f);
            return;
        }
        Evento ev = p.ev;
        if (ev != null && ocultarVivo(ev)) {
            aplicar();
        }
        volverFisico(p, vx, 0f);
    }

    private void navegar(int dir, float vx) {
        Evento a = cardEv;
        if (a == null) return;
        int i = -1;
        for (int k2 = 0; k2 < recientes.size(); k2++) {
            Evento r = recientes.get(k2);
            if (r == a || (a.clave != null && a.tipo == r.tipo && a.clave.equals(r.clave))) {
                i = k2;
                break;
            }
        }
        int destino = i < 0 ? recientes.size() - 1 : i + dir;
        if (destino < 0 || destino >= recientes.size() || recientes.isEmpty()) {
            dirSalida = 0;
            volverFisico(pCard, vx, 0f);
            reanudar();
            return;
        }
        Evento sig = recientes.get(destino);
        dirEntrada = dirSalida;
        cardEv = sig;
        cardVivo = false;
        trans = sig;
        banner = null;
        panelAcciones = false;
        programarCard(Math.max(3000, cfg.durationMs));
        aplicar();
    }

    private void largo(int obj) {
        vibrar(true);
        Pieza p = piezaDe(obj);
        if (p == null) return;
        Evento a = obj == 3 ? cardEv : (obj == 1 && p.clase == 2 ? banner : p.ev);
        if (a == null) return;
        if (a.tipo == Evento.MUSICA) {
            if (obj == 3) {
                musicaSilenciada = true;
                cardEv = null;
                cardVivo = false;
                aplicar();
            } else {
                tocar(obj);
            }
            return;
        }
        if (a.tipo == Evento.LLAMADA || a.tipo == Evento.LINTERNA || a.tipo == Evento.CRONO) {
            if (obj != 3) tocar(obj);
            return;
        }
        // Panel de acciones para notificaciones y avisos
        final Evento pn = a.copiar();
        final String pkg = a.pkg;
        ArrayList<String> txt = new ArrayList<>();
        ArrayList<Integer> col = new ArrayList<>();
        ArrayList<Runnable> run = new ArrayList<>();
        if (a.nativaTxt != null && a.nativaPi != null) {
            final PendingIntent pi = a.nativaPi;
            txt.add(String.valueOf(a.nativaTxt));
            col.add(0x33FFFFFF);
            run.add(new Runnable() {
                @Override
                public void run() {
                    try {
                        pi.send();
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
        }
        if (pkg != null) {
            txt.add("Minimizar");
            col.add(0x33FFFFFF);
            run.add(new Runnable() {
                @Override
                public void run() {
                    guardarLista(pkg, true);
                    confirmar("Minimizada", pkg);
                }
            });
            txt.add("Silenciar");
            col.add(0x66FF453A);
            run.add(new Runnable() {
                @Override
                public void run() {
                    guardarLista(pkg, false);
                    confirmar("Silenciada", pkg);
                }
            });
        }
        txt.add("Cerrar");
        col.add(0x33FFFFFF);
        run.add(new Runnable() {
            @Override
            public void run() {
                // solo cierra
            }
        });
        pn.botonTxt = txt.toArray(new String[0]);
        pn.botonColor = new int[col.size()];
        for (int i = 0; i < col.size(); i++) pn.botonColor[i] = col.get(i);
        pn.botonRun = run.toArray(new Runnable[0]);
        volverSinReanudar(p);
        cardEv = pn;
        cardVivo = false;
        trans = pn;
        banner = null;
        cancelar(bannerPend);
        bannerPend = null;
        panelAcciones = true;
        programarCard(6500);
        aplicar();
    }

    private void guardarLista(String pkg, boolean minim) {
        try {
            IslaConfig c = IslaConfig.load(ctx);
            if (minim) {
                c.minimizadas = IslaConfig.agregar(c.minimizadas, pkg);
            } else {
                c.blocked = IslaConfig.agregar(c.blocked, pkg);
            }
            Settings.Secure.putString(ctx.getContentResolver(), IslaConfig.KEY, c.serialize());
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private String etiquetaApp(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private void confirmar(final String titulo, final String pkg) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                Evento e = new Evento(Evento.AJUSTE);
                e.glifo = GlyphView.CHECK;
                e.color = 0xFF34C759;
                e.titulo = titulo;
                e.texto = etiquetaApp(pkg);
                e.duracionMs = 1800;
                try {
                    mostrarInterno(e);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }, 450);
    }

    /** true si hay una app a pantalla completa (la barra de estado esta oculta). */
    private boolean pantallaCompleta() {
        try {
            if (root == null) return false;
            android.view.WindowInsets wi = root.getRootWindowInsets();
            if (wi == null) return false;
            return !wi.isVisible(android.view.WindowInsets.Type.statusBars());
        } catch (Throwable t) {
            return false;
        }
    }
}
