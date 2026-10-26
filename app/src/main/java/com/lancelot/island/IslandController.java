package com.lancelot.island;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;

import de.robv.android.xposed.XposedBridge;

/**
 * Dibuja la isla dentro de SystemUI.
 *
 * Posicion: la capsula compacta se queda en el notch (con un hueco en medio para la camara)
 * y la expandida baja justo debajo de la barra de estado, asi nunca tapa la hora ni los iconos.
 * La ventana cambia de tamaño con el estado, de modo que en compacto casi no bloquea toques.
 *
 * Capsulas fijas: una llamada en curso (prioridad) o la musica quedan como capsula compacta
 * persistente. Los demas eventos aparecen encima, y despues vuelven a la capsula fija o se van.
 *
 * Notificaciones: pueden llegar expandidas o minimizadas (solo la capsula); tocar una minimizada
 * la expande. Gestos: tocar, mantener pulsado (acciones), deslizar arriba (descartar), deslizar
 * hacia abajo (expandir) y deslizar a los lados (cambiar de evento reciente).
 */
final class IslandController {

    private static final int ESPERA_COMPACTA_MS = 350;
    private static final int DURACION_MUSICA_MS = 6000;
    private static final int MINIMA_MS = 3200;
    private static final int MAX_RECIENTES = 6;
    private static final int LARGO_MS = 450;
    private static final int ROJO = 0xFFFF453A;
    private static final int GRIS = 0x33FFFFFF;

    private static IslandController inst;

    static synchronized IslandController get(Context c) {
        if (inst == null) inst = new IslandController(c);
        return inst;
    }

    /** Resorte amortiguado: sube, se pasa un poco y vuelve a su sitio. */
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

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** Cola de entrada: cancelarPasos() vacia h, y aqui no debe perderse ningun evento. */
    private final Handler hp = new Handler(Looper.getMainLooper());
    private final float density;

    private IslaConfig cfg;

    // Medidas (px), se calculan cada vez que aparece la isla
    private int sbPx;
    private int cutW;
    private int cutH;
    private int cutCx;
    private int compW;
    private int compH;
    private int compTop;
    private int compTx;
    private int expW;
    private int expTop;
    private boolean dividido;
    /** Ventana de sistema por encima de la barra de estado (para poder tocar la isla). */
    private static final int TIPO_SOBRE_BARRA = 2024; // TYPE_NAVIGATION_BAR_PANEL
    private int tipoVentana = TIPO_SOBRE_BARRA;

    private FrameLayout root;
    private IslandView pill;
    private IslaConfig cfgPill;
    private Evento actual;
    private Evento musica;
    private Evento llamada;
    private Evento linterna;
    private Evento crono;
    private final ArrayList<Evento> recientes = new ArrayList<>();

    private boolean shown;
    private boolean expanded;
    private boolean transitorio;
    private boolean ventanaGrande;
    private boolean musicaSilenciada;
    private boolean pantallaOn = true;
    private long ultimaNotif;

    private ValueAnimator sizeAnim;
    private float cornerPx;
    /** Cada paso nuevo sube el contador; los pasos programados de uno anterior se descartan. */
    private int gen;
    private Runnable colapsoPend;
    private int colapsoMs;

    // Gestos
    private float downX;
    private float downY;
    private boolean moviendo;
    private boolean largoHecho;
    private final Runnable largoRun = new Runnable() {
        @Override
        public void run() {
            if (!moviendo && !largoHecho) {
                largoHecho = true;
                try {
                    largo();
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }
    };

    private IslandController(Context c) {
        Context app = c.getApplicationContext();
        ctx = app != null ? app : c;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        density = ctx.getResources().getDisplayMetrics().density;
        cfg = IslaConfig.get(ctx);
    }

    // ------------------------------------------------------------------ API publica

    /** Muestra un evento puntual (notificacion, carga, linterna...). */
    void show(final Evento e) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mostrar(e);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /**
     * Progreso en directo (descargas). Si ya se ve esa descarga solo se actualiza el aro;
     * si no, nuevo indica si merece volver a aparecer (minimizada).
     */
    void progreso(final Evento e, final boolean nuevo) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cfg = IslaConfig.get(ctx);
                    if (!cfg.enabled || !cfg.permite(e.tipo) || !pantallaOn) return;
                    if (refrescarSiVisible(e)) return;
                    if (nuevo) {
                        e.minimizada = true;
                        mostrar(e);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Fija (o quita, con null) la musica persistente. anunciar: expandir un momento. */
    void setMusica(final Evento m, final boolean anunciar) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    fijar(Evento.MUSICA, m, anunciar);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Fija (o quita, con null) la llamada, que tiene prioridad sobre la musica. */
    void setLlamada(final Evento l, final boolean anunciar) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    fijar(Evento.LLAMADA, l, anunciar);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Fija (o quita, con null) el cronometro o temporizador de otra app. */
    void setCrono(final Evento l, final boolean anunciar) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    fijar(Evento.CRONO, l, anunciar);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Fija (o quita, con null) la linterna: queda la capsula mientras este encendida. */
    void setLinterna(final Evento l, final boolean anunciar) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    fijar(Evento.LINTERNA, l, anunciar);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Actualiza el contenido si ya se muestra un evento igual (porcentaje de carga). */
    void actualizarMismoTipo(final Evento e) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    refrescarSiVisible(e);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Los ajustes cambiaron: si hay una isla en pantalla se rehace con el estilo nuevo. */
    static void reaplicar() {
        final IslandController i;
        synchronized (IslandController.class) {
            i = inst;
        }
        if (i == null) return;
        i.hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    i.rehacerConConfig();
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    private void rehacerConConfig() {
        cfg = IslaConfig.get(ctx);
        if (!shown || pill == null || cfgPill == cfg) return;
        // La isla se dibuja con los ajustes con los que nacio: hay que construirla otra vez
        ArrayList<Evento> guardados = new ArrayList<>(recientes);
        cancelarPasos();
        quitar();
        recientes.addAll(guardados);
        restaurarFijo();
    }

    /** Pantalla encendida o apagada: con la pantalla apagada no queda nada dibujado. */
    void pantalla(final boolean on) {
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    pantallaOn = on;
                    if (!on) {
                        quitarTodo();
                    } else {
                        restaurarFijo();
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ flujo

    private Evento fijo() {
        if (llamada != null && cfg.fLlamada) return llamada;
        if (crono != null && cfg.fCrono) return crono;
        if (linterna != null && cfg.fLinterna) return linterna;
        if (musica != null && !musicaSilenciada && cfg.fMusica) return musica;
        return null;
    }

    private boolean esFijo(Evento e) {
        return e != null && (e.tipo == Evento.MUSICA || e.tipo == Evento.LLAMADA
                || e.tipo == Evento.LINTERNA || e.tipo == Evento.CRONO);
    }

    private boolean refrescarSiVisible(Evento e) {
        if (!shown || pill == null || actual == null || actual.tipo != e.tipo
                || !e.mismaClave(actual)) {
            return false;
        }
        e.minimizada = actual.minimizada;
        actual = e;
        reemplazarReciente(e);
        pill.configurar(e, expanded, false);
        return true;
    }

    private void registrar(Evento e) {
        if (esFijo(e)) return;
        reemplazarReciente(e);
        boolean esta = false;
        for (Evento r : recientes) {
            if (r == e) {
                esta = true;
                break;
            }
        }
        if (!esta) recientes.add(e);
        while (recientes.size() > MAX_RECIENTES) recientes.remove(0);
    }

    private void reemplazarReciente(Evento e) {
        if (e.clave == null) return;
        for (int i = 0; i < recientes.size(); i++) {
            Evento r = recientes.get(i);
            if (r.tipo == e.tipo && e.clave.equals(r.clave)) {
                recientes.set(i, e);
                return;
            }
        }
    }

    /** Una notificacion llega minimizada segun el modo elegido, la app y lo ocupada que este la isla. */
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
        boolean ocupada = fijo() != null || (shown && transitorio);
        boolean rafaga = ultimaNotif != 0
                && SystemClock.elapsedRealtime() - ultimaNotif < 8000;
        return ocupada || rafaga;
    }

    private void cancelarPasos() {
        h.removeCallbacksAndMessages(null);
        colapsoPend = null;
        gen++;
        cancelarAnimaciones();
    }

    private void mostrar(Evento e) {
        cfg = IslaConfig.get(ctx);
        if (!cfg.enabled || !cfg.permite(e.tipo) || !pantallaOn || horizontalOculto()) return;

        // El volumen se actualiza en el sitio mientras sigues pulsando los botones
        if (e.tipo == Evento.VOLUMEN && shown && actual != null && actual.tipo == Evento.VOLUMEN
                && pill != null) {
            actual = e;
            pill.configurar(e, expanded, false);
            if (expanded) programarColapso(gen, 1800);
            return;
        }

        // Mensajes seguidos de la misma app: se agrupan en la isla que ya se ve
        if (e.tipo == Evento.NOTIF && shown && pill != null && actual != null
                && actual.tipo == Evento.NOTIF && e.pkg != null && e.pkg.equals(actual.pkg)
                && SystemClock.elapsedRealtime() - ultimaNotif < 20000) {
            e.agrupadas = actual.agrupadas + 1;
            e.minimizada = actual.minimizada;
            e.contador = actual.contador;
            ultimaNotif = SystemClock.elapsedRealtime();
            registrar(e);
            actual = e;
            pill.configurar(e, expanded, false);
            if (expanded) {
                reiniciarColapso();
            } else {
                pill.destello(e.color);
            }
            return;
        }

        boolean min = decidirMinimizada(e);
        registrar(e);
        if (e.tipo == Evento.NOTIF) ultimaNotif = SystemClock.elapsedRealtime();

        // Hay algo expandido y llega una minimizada: no se interrumpe, solo un destello
        if (min && shown && pill != null && expanded) {
            pill.destello(e.color);
            return;
        }

        cancelarPasos();
        final int g = gen;
        transitorio = true;
        actual = e;
        e.minimizada = min;
        if (e.tipo == Evento.NOTIF) {
            int n = 0;
            for (Evento r : recientes) {
                if (r.tipo == Evento.NOTIF) n++;
            }
            e.contador = n;
        }

        if (shown && pill != null) {
            pill.setAlpha(1f);
            pill.setScaleX(1f);
            pill.setScaleY(1f);
            pill.setTranslationY(0f);
            pill.setTranslationX(expanded ? 0f : compTx);
            if (expanded) {
                expandir(g);
            } else {
                pill.configurar(e, false, false);
                pill.destello(e.color);
                if (cfg.style == 0) gota();
                if (min) {
                    programarColapso(g, e.duracionMs > 0 ? e.duracionMs : minimaMs());
                } else {
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (g == gen) expandir(g);
                        }
                    }, 120);
                }
            }
            return;
        }

        construir(e);
        shown = true;
        expanded = false;
        entrar();
        pill.destello(e.color);
        if (cfg.ocultarFs && e.tipo != Evento.LLAMADA) {
            final IslandView pv = pill;
            pv.post(new Runnable() {
                @Override
                public void run() {
                    if (pill == pv && pv.pantallaCompleta()) quitar();
                }
            });
        }
        if (min) {
            programarColapso(g, e.duracionMs > 0 ? e.duracionMs : minimaMs());
        } else {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (g == gen) expandir(g);
                }
            }, cfg.ms(ESPERA_COMPACTA_MS));
        }
    }

    /** Hyper: la capsula se estira y vuelve, como una gota, cuando cambia el contenido. */
    private void gota() {
        if (pill == null) return;
        pill.setScaleX(1.2f);
        pill.setScaleY(0.86f);
        pill.animate().scaleX(1f).scaleY(1f).setDuration(cfg.ms(420))
                .setInterpolator(new Muelle(5f, 1.3f)).start();
    }

    private volatile boolean respondiendo;

    /** Boton Responder de una notificacion: el campo de texto aparece en la isla. */
    void iniciarRespuesta() {
        respondiendo = true;
        hp.post(new Runnable() {
            @Override
            public void run() {
                try {
                    empezarRespuesta();
                } catch (Throwable t) {
                    respondiendo = false;
                    XposedBridge.log(t);
                }
            }
        });
    }

    private void empezarRespuesta() {
        final Evento e = actual;
        if (pill == null || e == null || e.respPi == null || e.respRi == null || !expanded) {
            respondiendo = false;
            return;
        }
        cancelarPasos();
        try {
            wm.updateViewLayout(root, params(true));
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        final android.widget.EditText et = pill.mostrarRespuesta("Responder a " + e.titulo,
                new IslandView.AlResponder() {
                    @Override
                    public void enviar(String texto) {
                        enviarRespuesta(e, texto);
                    }

                    @Override
                    public void cancelar() {
                        terminarRespuesta(false);
                    }
                });
        et.requestFocus();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, 0);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }, 250);
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

    private void terminarRespuesta(boolean enviado) {
        respondiendo = false;
        if (pill == null) return;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(pill.getWindowToken(), 0);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        try {
            wm.updateViewLayout(root, params(true));
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        pill.ocultarRespuesta();
        if (enviado) {
            descartar(0f, -dp(30));
        } else {
            programarColapso(gen, 3500);
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

    private int minimaMs() {
        return Math.max(MINIMA_MS, cfg.durationMs - 600);
    }

    /** Capsula persistente (musica o llamada). tipo decide cual de las dos. */
    private void fijar(int tipo, Evento m, boolean anunciar) {
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
            if (shown && actual != null && actual.tipo == tipo && !esTransitorioReal()) {
                // Era justo la capsula fija que se acaba de ir
                if (expanded) {
                    cancelarPasos();
                    colapsar(gen);
                } else {
                    restaurarFijo();
                }
            } else if (shown && !transitorio) {
                restaurarFijo();
            }
            return;
        }

        if (anunciar && tipo == Evento.MUSICA) musicaSilenciada = false;
        if (tipo == Evento.MUSICA && ((llamada != null && cfg.fLlamada)
                || (linterna != null && cfg.fLinterna) || (crono != null && cfg.fCrono))) {
            return;
        }
        if ((tipo == Evento.LINTERNA || tipo == Evento.CRONO) && llamada != null
                && cfg.fLlamada) {
            return;
        }
        if (tipo == Evento.LINTERNA && crono != null && cfg.fCrono) return;
        if (!cfg.enabled || !cfg.permite(tipo) || !pantallaOn || horizontalOculto()) return;
        if (tipo == Evento.MUSICA && musicaSilenciada) return;

        if (transitorio) {
            // Hay otro evento en pantalla: al terminar volvera a la capsula fija.
            // Si lo que se ve es esa misma capsula expandida, se refresca.
            if (expanded && actual != null && actual.tipo == tipo && pill != null) {
                actual = m;
                pill.configurar(m, true, false);
            } else if (anunciar && (tipo == Evento.LLAMADA || tipo == Evento.LINTERNA
                    || tipo == Evento.CRONO)) {
                // Una llamada o la linterna interrumpen lo que se este viendo
                mostrarFijo(m, true);
            }
            return;
        }
        mostrarFijo(m, anunciar);
    }

    /** true si lo que hay en pantalla es un evento puntual y no la capsula fija. */
    private boolean esTransitorioReal() {
        return transitorio && !esFijo(actual);
    }

    private void mostrarFijo(Evento m, boolean anunciar) {
        if (!shown) {
            cancelarPasos();
            actual = m;
            construir(m);
            shown = true;
            expanded = false;
            transitorio = false;
            entrar();
            if (anunciar) anunciarFijo();
            return;
        }
        actual = m;
        if (anunciar) {
            anunciarFijo();
        } else if (pill != null) {
            pill.configurar(m, expanded, false);
        }
    }

    private void anunciarFijo() {
        transitorio = true;
        // Solo los temporizadores: la animacion de entrada que este en marcha sigue su curso
        h.removeCallbacksAndMessages(null);
        colapsoPend = null;
        gen++;
        final int g = gen;
        if (pill != null) {
            pill.destello(actual != null ? actual.color : 0xFFFFFFFF);
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) expandir(g);
            }
        }, cfg.ms(ESPERA_COMPACTA_MS));
    }

    /** Vuelve a la capsula fija si la hay; si no, quita la isla. */
    private void restaurarFijo() {
        Evento f = fijo();
        if (f == null) {
            if (shown && !transitorio) {
                cancelarPasos();
                salir(gen);
            }
            return;
        }
        if (transitorio) return;
        if (!shown) {
            mostrarFijo(f, false);
        } else if (pill != null) {
            actual = f;
            pill.configurar(f, false, false);
        }
    }

    private void entrar() {
        pill.setAlpha(0f);
        pill.setScaleX(0.6f);
        pill.setScaleY(0.6f);
        if (cfg.style == 1) {
            // Cristal: aparece "desde cerca" y se enfoca
            pill.setScaleX(1.14f);
            pill.setScaleY(1.14f);
            pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(cfg.ms(430))
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            pill.enfocar(cfg.ms(560));
            return;
        }
        if (cfg.style == 2) {
            // Neon: se enciende como un tubo, sin cambiar de tamaño
            pill.setScaleX(1f);
            pill.setScaleY(1f);
            pill.setAlpha(1f);
            pill.parpadeo();
            return;
        }
        TimeInterpolator ip = cfg.animStyle == 0 ? new DecelerateInterpolator(1.4f)
                : new Muelle(5.5f, cfg.animStyle == 2 ? 1.5f : 1.0f);
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(cfg.ms(cfg.animStyle == 0 ? 240 : 420))
                .setInterpolator(ip)
                .start();
    }

    private void expandir(int g) {
        if (pill == null || actual == null) return;
        expanded = true;
        ventana(true);
        pill.configurar(actual, true, true);
        pill.destello(actual.color);
        if (cfg.haptic) vibrar(false);
        int eh = dp(IslandView.alturaDp(cfg, actual));
        float radio = Math.min(dp(cfg.radiusDp), eh / 2f);
        if (cfg.style == 1) pill.enfocar(cfg.ms(520));
        if (cfg.style == 2) pill.parpadeo();
        switch (cfg.style == 0 ? cfg.animStyle : 10 + cfg.style) {
            case 11:
                // Cristal: movimiento lento y sedoso, sin rebote
                animarRect(expW, eh, expTop, 0f, radio, cfg.ms(560),
                        new DecelerateInterpolator(2.4f), new DecelerateInterpolator(2f), null, g);
                break;
            case 12:
                // Neon: seco y rapido
                animarRect(expW, eh, expTop, 0f, radio, cfg.ms(230),
                        new android.view.animation.AccelerateDecelerateInterpolator(),
                        new android.view.animation.AccelerateDecelerateInterpolator(), null, g);
                break;
            case 0:
                animarRect(expW, eh, expTop, 0f, radio, cfg.ms(340),
                        new DecelerateInterpolator(1.4f), new DecelerateInterpolator(1.4f), null, g);
                break;
            case 2:
                // Elastico: ancho y alto rebotan con distinta frecuencia (efecto gelatina)
                animarRect(expW, eh, expTop, 0f, radio, cfg.ms(560),
                        new Muelle(5.2f, 1.5f), new Muelle(4.6f, 1.15f), null, g);
                break;
            default:
                animarRect(expW, eh, expTop, 0f, radio, cfg.ms(460),
                        new Muelle(6f, 1.0f), new Muelle(6f, 1.0f), null, g);
                break;
        }
        int ms = actual.duracionMs > 0 ? actual.duracionMs
                : (actual.tipo == Evento.MUSICA ? DURACION_MUSICA_MS : cfg.durationMs);
        if (actual.tipo == Evento.LLAMADA && actual.sub == 1) ms = 0;
        if (actual.botonTxt != null && actual.botonTxt.length > 0 && actual.tipo != Evento.LLAMADA) {
            ms = Math.max(ms, 6000);
        }
        colapsoMs = ms;
        if (ms > 0) programarColapso(g, ms);
    }

    private void programarColapso(final int g, int ms) {
        if (colapsoPend != null) h.removeCallbacks(colapsoPend);
        colapsoMs = ms;
        colapsoPend = new Runnable() {
            @Override
            public void run() {
                if (g != gen) return;
                if (expanded) {
                    colapsar(g);
                } else {
                    despuesDeColapsar(g, 0);
                }
            }
        };
        h.postDelayed(colapsoPend, ms);
        // Seguro: si el cierre normal no ocurrio, se fuerza para que nada se quede pegado
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g != gen || !shown || pill == null) return;
                XposedBridge.log("[IslaSystemUI] cierre forzado (tipo="
                        + (actual != null ? actual.tipo : -1) + ")");
                cancelarPasos();
                if (expanded) {
                    colapsar(gen);
                } else if (esFijo(actual)) {
                    despuesDeColapsar(gen, 0);
                } else {
                    quitar();
                    restaurarFijo();
                }
            }
        }, ms + 6000L);
    }

    private void pausarColapso() {
        if (colapsoPend != null) h.removeCallbacks(colapsoPend);
    }

    /** El usuario toco un control: el temporizador empieza de nuevo. */
    private void reiniciarColapso() {
        if (!shown || !expanded || colapsoMs <= 0) return;
        programarColapso(gen, colapsoMs);
    }

    private void colapsar(final int g) {
        if (pill == null) return;
        animarRect(compW, compH, compTop, compTx, compH / 2f,
                cfg.ms(cfg.style == 1 ? 380 : cfg.style == 2 ? 190 : 260),
                new DecelerateInterpolator(), new DecelerateInterpolator(),
                new Runnable() {
                    @Override
                    public void run() {
                        if (pill == null) return;
                        expanded = false;
                        ventana(false);
                        despuesDeColapsar(g, 450);
                    }
                }, g);
    }

    /** Termina un evento puntual: vuelve a la capsula fija o se va. */
    private void despuesDeColapsar(final int g, int esperaMs) {
        if (pill == null) return;
        Evento f = fijo();
        if (f != null) {
            actual = f;
            transitorio = false;
            pill.configurar(f, false, false);
        } else {
            pill.configurar(actual, false, false);
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (g == gen) salir(g);
                }
            }, esperaMs);
        }
    }

    private void salir(final int g) {
        if (pill == null) {
            quitar();
            return;
        }
        float fin = cfg.style == 1 ? 0.92f : cfg.style == 2 ? 1f : 0.6f;
        pill.animate().alpha(0f).scaleX(fin).scaleY(fin)
                .setDuration(cfg.ms(cfg.style == 1 ? 300 : cfg.style == 2 ? 150 : 200))
                .setInterpolator(new AccelerateInterpolator())
                .start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) quitar();
            }
        }, cfg.ms(220));
    }

    /** Descarta la isla con un deslizamiento (arriba o a un lado). */
    private void descartar(final float dx, final float dy) {
        cancelarPasos();
        final int g = gen;
        if (pill == null) return;

        if (expanded && esFijo(actual)) {
            // La musica o la llamada expandida solo se encoge
            pill.animate().translationX(0f).translationY(0f).setDuration(120).start();
            colapsar(g);
            return;
        }
        if (!expanded && esFijo(actual)) {
            if (actual.tipo == Evento.LLAMADA || actual.tipo == Evento.LINTERNA) {
                // La llamada y la linterna no se esconden mientras siguen activas
                pill.animate().translationX(compTx).translationY(0f).setDuration(120).start();
                return;
            }
            // Capsula de musica: se oculta hasta la proxima cancion
            musicaSilenciada = true;
        }
        pill.animate().translationX(pill.getTranslationX() + dx).translationY(dy).alpha(0f)
                .setDuration(cfg.ms(170)).start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g != gen) return;
                quitar();
                // Si habia una capsula fija debajo, vuelve a su sitio
                restaurarFijo();
            }
        }, cfg.ms(180));
    }

    /** Toque: expande una capsula compacta; en expandida abre la accion del evento. */
    private long ultimoToque;

    private void tocar() {
        if (pill == null || actual == null) return;
        long ahora = SystemClock.elapsedRealtime();
        boolean doble = cfg.dobleToque && ahora - ultimoToque < 350;
        ultimoToque = ahora;
        if (doble && expanded) {
            // Doble toque rapido: se descarta sin abrir nada
            if (esFijo(actual)) {
                cancelarPasos();
                colapsar(gen);
            } else {
                descartar(0f, -dp(30));
            }
            return;
        }
        if (!expanded) {
            cancelarPasos();
            transitorio = true;
            pill.setTranslationY(0f);
            pill.setTranslationX(compTx);
            expandir(gen);
            return;
        }
        PendingIntent p = actual.accion;
        boolean fija = esFijo(actual);
        if (fija) {
            cancelarPasos();
            colapsar(gen);
        } else {
            descartar(0f, -dp(30));
        }
        if (p != null) {
            try {
                p.send();
            } catch (Throwable e) {
                XposedBridge.log(e);
            }
        }
    }

    /** Mantener pulsado: expande y, en notificaciones, abre el panel de acciones. */
    private void largo() {
        if (pill == null || actual == null) return;
        vibrar(true);
        Evento a = actual;
        if (a.tipo == Evento.MUSICA) {
            if (!expanded) {
                tocar();
            } else {
                // Expandida: ocultar la musica hasta la proxima cancion
                musicaSilenciada = true;
                cancelarPasos();
                colapsar(gen);
            }
            return;
        }
        if (a.tipo == Evento.LLAMADA || a.tipo == Evento.LINTERNA) {
            if (!expanded) tocar();
            return;
        }
        // Panel de acciones: la isla se expande con botones
        final Evento p = a.copiar();
        final String pkg = a.pkg;
        ArrayList<String> txt = new ArrayList<>();
        ArrayList<Integer> col = new ArrayList<>();
        ArrayList<Runnable> run = new ArrayList<>();
        if (a.nativaTxt != null && a.nativaPi != null) {
            final PendingIntent pi = a.nativaPi;
            txt.add(String.valueOf(a.nativaTxt));
            col.add(GRIS);
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
            col.add(GRIS);
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
        col.add(GRIS);
        run.add(new Runnable() {
            @Override
            public void run() {
                // solo cierra
            }
        });
        p.botonTxt = txt.toArray(new String[0]);
        p.botonColor = new int[col.size()];
        for (int i = 0; i < col.size(); i++) p.botonColor[i] = col.get(i);
        p.botonRun = run.toArray(new Runnable[0]);

        cancelarPasos();
        transitorio = true;
        actual = p;
        pill.setTranslationY(0f);
        pill.setTranslationX(expanded ? 0f : compTx);
        expandir(gen);
    }

    /** Guarda en los ajustes que una app se silencie (minim=false) o llegue minimizada. */
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

    private void confirmar(final String titulo, final String pkg) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                Evento e = new Evento(Evento.AJUSTE);
                e.glifo = GlyphView.CHECK;
                e.color = 0xFF34C759;
                e.titulo = titulo;
                e.texto = etiqueta(pkg);
                e.duracionMs = 1800;
                try {
                    mostrar(e);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }, 450);
    }

    private String etiqueta(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** Deslizar a los lados con la isla expandida: pasa al evento reciente anterior o siguiente. */
    private void navegar(final int dir) {
        if (!expanded || pill == null) {
            return;
        }
        int i = -1;
        for (int k = 0; k < recientes.size(); k++) {
            if (recientes.get(k) == actual
                    || (actual != null && actual.clave != null && actual.tipo == recientes.get(k).tipo
                    && actual.clave.equals(recientes.get(k).clave))) {
                i = k;
                break;
            }
        }
        int destino;
        if (i < 0) {
            destino = recientes.size() - 1;
        } else {
            // Deslizar a la izquierda (dir>0) = mas reciente, a la derecha = mas antiguo
            destino = i + dir;
        }
        if (destino < 0 || destino >= recientes.size() || recientes.isEmpty()) {
            // No hay mas: rebote elastico
            pill.animate().translationX(0f).translationY(0f).setDuration(cfg.ms(260))
                    .setInterpolator(new Muelle(6f, 1.4f)).start();
            reanudarColapso();
            return;
        }
        final Evento sig = recientes.get(destino);
        cancelarPasos();
        final int g = gen;
        transitorio = true;
        pill.animate().translationX(-dir * dp(90)).alpha(0f).setDuration(cfg.ms(110))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (g != gen || pill == null) return;
                        actual = sig;
                        pill.configurar(sig, true, true);
                        pill.setTranslationX(dir * dp(70));
                        pill.animate().translationX(0f).alpha(1f).setDuration(cfg.ms(220))
                                .setInterpolator(new DecelerateInterpolator()).start();
                        int eh = dp(IslandView.alturaDp(cfg, sig));
                        animarRect(expW, eh, expTop, 0f, Math.min(dp(cfg.radiusDp), eh / 2f),
                                cfg.ms(260), new DecelerateInterpolator(),
                                new DecelerateInterpolator(), null, g);
                        programarColapso(g, cfg.durationMs);
                    }
                }).start();
    }

    private void reanudarColapso() {
        if (!shown || !expanded || colapsoMs <= 0) return;
        programarColapso(gen, Math.max(2000, colapsoMs / 2));
    }

    private void vibrar(boolean fuerte) {
        try {
            Vibrator v = ctx.getSystemService(Vibrator.class);
            if (v == null) return;
            v.vibrate(VibrationEffect.createPredefined(fuerte ? VibrationEffect.EFFECT_CLICK
                    : VibrationEffect.EFFECT_TICK));
        } catch (Throwable ignorado) {
            // sin vibrador o sin permiso: no pasa nada
        }
    }

    private void quitarTodo() {
        cancelarPasos();
        quitar();
    }

    private void quitar() {
        if (!shown) {
            transitorio = false;
            return;
        }
        shown = false;
        expanded = false;
        transitorio = false;
        respondiendo = false;
        cancelarAnimaciones();
        try {
            if (root != null) wm.removeViewImmediate(root);
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
        root = null;
        pill = null;
        ventanaGrande = false;
        recientes.clear();
    }

    // ------------------------------------------------------------------ medidas y ventana

    private void medir() {
        sbPx = 0;
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) sbPx = ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignorado) {
            // se usa el valor de reserva
        }
        if (sbPx <= 0) sbPx = dp(24);

        cutW = 0;
        cutH = 0;
        cutCx = 0;
        try {
            WindowMetrics m = wm.getCurrentWindowMetrics();
            int pantalla = m.getBounds().width();
            DisplayCutout dc = m.getWindowInsets().getDisplayCutout();
            if (dc != null) {
                Rect r = dc.getBoundingRectTop();
                if (r != null && !r.isEmpty()) {
                    cutW = r.width();
                    cutH = r.bottom;
                    cutCx = r.centerX() - pantalla / 2;
                }
            }
        } catch (Throwable ignorado) {
            // sin datos del notch: se usa la capsula normal
        }

        dividido = cutW > 0;
        if (dividido) {
            // Alrededor del notch: icono a un lado, indicador al otro
            compW = Math.max(dp(cfg.compactW), cutW + 2 * dp(34));
            compH = Math.max(dp(26), Math.min(cutH + dp(2), Math.max(sbPx, dp(26))));
            compTop = 0;
            compTx = cutCx;
        } else {
            compW = dp(cfg.compactW);
            compH = dp(30);
            compTop = dp(4);
            compTx = 0;
        }
        expW = dp(cfg.widthDp);
        // Expandida: debajo de la barra de estado (o encima de ella si asi se elige)
        expTop = (cfg.expandedPos == 0 ? sbPx + dp(2) : 0) + dp(cfg.topDp);
    }

    private WindowManager.LayoutParams params(boolean grande) {
        int w;
        int hh;
        if (grande) {
            // Margen extra para que el rebote del resorte no se recorte
            w = expW + dp(64);
            hh = expTop + dp(IslandView.alturaMaxDp(cfg)) + dp(28);
        } else {
            w = compW + 2 * Math.abs(compTx) + dp(24);
            hh = compTop + compH + dp(8);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, hh,
                tipoVentana,
                (respondiendo ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("IslaSystemUI");
        return lp;
    }

    /** Cambia el tamaño de la ventana segun el estado: grande al expandir, pequeña en compacto. */
    private void ventana(boolean grande) {
        if (root == null || ventanaGrande == grande) return;
        ventanaGrande = grande;
        try {
            wm.updateViewLayout(root, params(grande));
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }

    // ------------------------------------------------------------------ vistas y animacion

    private float baseTx() {
        return expanded ? 0f : compTx;
    }

    private void construir(Evento e) {
        medir();
        root = new FrameLayout(ctx);
        cfgPill = cfg;
        pill = new IslandView(ctx, cfg, dividido ? cutW : 0);
        pill.configurar(e, false, false);
        cornerPx = compH / 2f;
        pill.setCorner(cornerPx);
        pill.setAlInteraccion(new Runnable() {
            @Override
            public void run() {
                reiniciarColapso();
            }
        });
        pill.setAlBoton(new Runnable() {
            @Override
            public void run() {
                // Contestar o colgar: la notificacion de la llamada cambiara sola
                if (esFijo(actual) || respondiendo) return;
                descartar(0f, -dp(30));
            }
        });

        pill.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (pill == null) return false;
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        moviendo = false;
                        largoHecho = false;
                        pausarColapso();
                        h.postDelayed(largoRun, LARGO_MS);
                        pill.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moviendo && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                            moviendo = true;
                            h.removeCallbacks(largoRun);
                        }
                        if (moviendo && !largoHecho) {
                            if (Math.abs(dx) > Math.abs(dy)) {
                                pill.setTranslationX(baseTx() + dx * 0.7f);
                            } else if (dy < 0) {
                                pill.setTranslationY(dy);
                            } else if (!expanded) {
                                pill.setTranslationY(Math.min(dy * 0.35f, dp(14)));
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        h.removeCallbacks(largoRun);
                        soltar();
                        if (largoHecho) {
                            reanudarColapso();
                            return true;
                        }
                        if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) {
                            if (expanded && actual != null && actual.tipo == Evento.MUSICA
                                    && actual.control != null) {
                                // Cambiar de cancion con un gesto
                                if (dx < 0) {
                                    actual.siguiente();
                                } else {
                                    actual.anterior();
                                }
                                volverASitio();
                                reanudarColapso();
                            } else if (expanded) {
                                navegar(dx < 0 ? 1 : -1);
                            } else {
                                descartar(dx < 0 ? -dp(60) : dp(60), 0f);
                            }
                        } else if (dy < -dp(24)) {
                            descartar(0f, -dp(30));
                        } else if (dy > dp(24) && !expanded) {
                            tocar();
                        } else if (Math.abs(dx) < dp(8) && Math.abs(dy) < dp(8)) {
                            tocar();
                        } else {
                            volverASitio();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        h.removeCallbacks(largoRun);
                        soltar();
                        volverASitio();
                        return true;
                    default:
                        return false;
                }
            }
        });

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                compW, compH, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.topMargin = compTop;
        root.addView(pill, plp);
        pill.setTranslationX(compTx);
        pill.setPivotX(compW / 2f);
        pill.setPivotY(0f);

        ventanaGrande = false;
        try {
            wm.addView(root, params(false));
        } catch (Throwable t) {
            // Algunas ROM no dejan ese tipo de ventana: se usa la de superposicion normal,
            // que queda por debajo de la barra de estado (no se puede tocar en el notch)
            XposedBridge.log("[IslaSystemUI] ventana sobre la barra no disponible: " + t);
            tipoVentana = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            try {
                wm.addView(root, params(false));
            } catch (Throwable t2) {
                XposedBridge.log(t2);
            }
        }
        pill.activarBlur();
        pill.setCorner(cornerPx);
        XposedBridge.log("[IslaSystemUI] ventana anadida (notch=" + cutW + "px, barra=" + sbPx
                + "px, blur=" + pill.estadoBlur() + ")");
    }

    /** Al soltar, la isla recupera su tamaño con un pequeño rebote (como al pulsar en iOS). */
    private void soltar() {
        if (pill == null) return;
        pill.animate().scaleX(1f).scaleY(1f).setDuration(cfg.ms(340))
                .setInterpolator(cfg.animStyle == 0 ? new DecelerateInterpolator()
                        : new Muelle(6f, 1.4f))
                .start();
    }

    /** Suelta sin gesto valido: vuelve a su sitio con un pequeño rebote. */
    private void volverASitio() {
        if (pill == null) return;
        pill.animate().translationX(baseTx()).translationY(0f).setDuration(cfg.ms(240))
                .setInterpolator(cfg.animStyle == 0 ? new DecelerateInterpolator()
                        : new Muelle(6f, 1.3f))
                .start();
        reanudarColapso();
    }

    private void animarRect(final int toW, final int toH, final int toTop, final float toTx,
                            final float toR, int ms, final TimeInterpolator ipW,
                            final TimeInterpolator ipH, final Runnable alTerminar, final int g) {
        if (pill == null) return;
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        final int fromW = lp.width;
        final int fromH = lp.height;
        final int fromTop = lp.topMargin;
        final float fromTx = pill.getTranslationX();
        final float fromR = cornerPx;

        if (sizeAnim != null) sizeAnim.cancel();
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        sizeAnim = va;
        va.setDuration(ms);
        va.setInterpolator(null);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (g != gen || pill == null) return;
                float f = (Float) a.getAnimatedValue();
                float fw = ipW.getInterpolation(f);
                float fh = ipH.getInterpolation(f);
                lp.width = Math.max(1, Math.round(fromW + (toW - fromW) * fw));
                lp.height = Math.max(1, Math.round(fromH + (toH - fromH) * fh));
                lp.topMargin = Math.round(fromTop + (toTop - fromTop) * fh);
                pill.setLayoutParams(lp);
                pill.setPivotX(lp.width / 2f);
                pill.setTranslationX(fromTx + (toTx - fromTx) * fw);
                cornerPx = Math.max(0f, fromR + (toR - fromR) * fh);
                pill.setCorner(Math.min(cornerPx, lp.height / 2f));
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            private boolean cancelada;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelada = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelada || g != gen || pill == null) return;
                lp.width = toW;
                lp.height = toH;
                lp.topMargin = toTop;
                pill.setLayoutParams(lp);
                pill.setPivotX(toW / 2f);
                pill.setTranslationX(toTx);
                cornerPx = toR;
                pill.setCorner(toR);
                if (alTerminar != null) alTerminar.run();
            }
        });
        va.start();
    }

    private void cancelarAnimaciones() {
        if (sizeAnim != null) {
            sizeAnim.cancel();
            sizeAnim = null;
        }
        if (pill != null) {
            // Una animacion cortada a medias no puede dejar la isla pequeña o transparente
            pill.animate().cancel();
            pill.setAlpha(1f);
            pill.setScaleX(1f);
            pill.setScaleY(1f);
            pill.setTranslationY(0f);
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
