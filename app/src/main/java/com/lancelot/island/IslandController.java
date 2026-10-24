package com.lancelot.island;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import de.robv.android.xposed.XposedBridge;

/**
 * Dibuja la isla dentro de SystemUI.
 *
 * Posicion: la capsula compacta se queda en el notch (con un hueco en medio para la camara)
 * y la expandida baja justo debajo de la barra de estado, asi nunca tapa la hora ni los iconos.
 * La ventana cambia de tamaño con el estado, de modo que en compacto casi no bloquea toques.
 *
 * Musica: mientras suena queda una capsula compacta persistente con ecualizador.
 * Los demas eventos aparecen encima, se expanden, y despues vuelven a la musica o desaparecen.
 */
final class IslandController {

    private static final int ESPERA_COMPACTA_MS = 350;
    private static final int DURACION_MUSICA_MS = 6000;

    private static IslandController inst;

    static synchronized IslandController get(Context c) {
        if (inst == null) inst = new IslandController(c);
        return inst;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
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

    private FrameLayout root;
    private IslandView pill;
    private Evento actual;
    private Evento musica;

    private boolean shown;
    private boolean expanded;
    private boolean transitorio;
    private boolean ventanaGrande;
    private boolean musicaSilenciada;
    private boolean pantallaOn = true;

    private ValueAnimator sizeAnim;
    private float cornerPx;
    /** Cada paso nuevo sube el contador; los pasos programados de uno anterior se descartan. */
    private int gen;
    private float downY;

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
        h.post(new Runnable() {
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

    /** Fija (o quita, con null) la musica persistente. anunciar: expandir un momento. */
    void setMusica(final Evento m, final boolean anunciar) {
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    fijarMusica(m, anunciar);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Actualiza el contenido si ya se muestra un evento del mismo tipo (porcentaje de carga). */
    void actualizarMismoTipo(final Evento e) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (shown && pill != null && expanded && actual != null && actual.tipo == e.tipo) {
                    actual = e;
                    pill.configurar(e, true, false);
                }
            }
        });
    }

    /** Pantalla encendida o apagada: con la pantalla apagada no queda nada dibujado. */
    void pantalla(final boolean on) {
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    pantallaOn = on;
                    if (!on) {
                        quitarTodo();
                    } else if (musicaActiva()) {
                        fijarMusica(musica, false);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ flujo

    private boolean musicaActiva() {
        return musica != null && !musicaSilenciada && cfg.fMusica;
    }

    private void mostrar(Evento e) {
        cfg = IslaConfig.get(ctx);
        if (!cfg.enabled || !cfg.permite(e.tipo) || !pantallaOn) return;

        h.removeCallbacksAndMessages(null);
        gen++;
        final int g = gen;
        cancelarAnimaciones();
        transitorio = true;
        actual = e;

        if (shown && pill != null) {
            pill.setAlpha(1f);
            pill.setScaleX(1f);
            pill.setScaleY(1f);
            pill.setTranslationY(0f);
            if (expanded) {
                expandir(g);
            } else {
                pill.configurar(e, false, false);
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (g == gen) expandir(g);
                    }
                }, 120);
            }
            return;
        }

        construir(e);
        shown = true;
        expanded = false;
        entrar();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) expandir(g);
            }
        }, ESPERA_COMPACTA_MS);
    }

    private void fijarMusica(Evento m, boolean anunciar) {
        cfg = IslaConfig.get(ctx);
        musica = m;
        if (m == null) {
            if (shown && !transitorio) {
                h.removeCallbacksAndMessages(null);
                gen++;
                cancelarAnimaciones();
                salir(gen);
            }
            return;
        }
        if (anunciar) musicaSilenciada = false;
        if (!cfg.enabled || !cfg.fMusica || musicaSilenciada || !pantallaOn) return;

        if (transitorio) {
            // Hay otro evento en pantalla: al terminar volvera a la musica.
            // Si lo que se ve es la propia musica expandida, se refresca.
            if (expanded && actual != null && actual.tipo == Evento.MUSICA && pill != null) {
                actual = m;
                pill.configurar(m, true, false);
            }
            return;
        }

        if (!shown) {
            h.removeCallbacksAndMessages(null);
            gen++;
            cancelarAnimaciones();
            actual = m;
            construir(m);
            shown = true;
            expanded = false;
            entrar();
            if (anunciar) anunciarMusica();
            return;
        }

        actual = m;
        if (anunciar) {
            anunciarMusica();
        } else if (pill != null) {
            pill.configurar(m, false, false);
        }
    }

    private void anunciarMusica() {
        transitorio = true;
        h.removeCallbacksAndMessages(null);
        gen++;
        final int g = gen;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) expandir(g);
            }
        }, ESPERA_COMPACTA_MS);
    }

    /** Toque sobre la capsula compacta de la musica: expandirla. */
    private void expandirMusica() {
        if (!musicaActiva()) return;
        transitorio = true;
        h.removeCallbacksAndMessages(null);
        gen++;
        cancelarAnimaciones();
        actual = musica;
        expandir(gen);
    }

    private void entrar() {
        pill.setAlpha(0f);
        pill.setScaleX(0.6f);
        pill.setScaleY(0.6f);
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void expandir(int g) {
        if (pill == null || actual == null) return;
        expanded = true;
        ventana(true);
        pill.configurar(actual, true, true);
        int eh = dp(IslandView.alturaDp(cfg, actual));
        TimeInterpolator ip = cfg.bounce ? new OvershootInterpolator(0.9f)
                : new DecelerateInterpolator();
        animarRect(expW, eh, expTop, 0f, Math.min(dp(cfg.radiusDp), eh / 2f), 380, ip, null, g);
        programarColapso(g, actual.tipo == Evento.MUSICA ? DURACION_MUSICA_MS : cfg.durationMs);
    }

    private void programarColapso(final int g, int ms) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) colapsar(g);
            }
        }, ms);
    }

    private void colapsar(final int g) {
        if (pill == null) return;
        animarRect(compW, compH, compTop, compTx, compH / 2f, 260, new DecelerateInterpolator(),
                new Runnable() {
                    @Override
                    public void run() {
                        if (pill == null) return;
                        expanded = false;
                        ventana(false);
                        if (musicaActiva()) {
                            // Vuelve a la capsula persistente de la musica
                            actual = musica;
                            transitorio = false;
                            pill.configurar(musica, false, false);
                        } else {
                            pill.configurar(actual, false, false);
                            h.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (g == gen) salir(g);
                                }
                            }, 450);
                        }
                    }
                }, g);
    }

    private void salir(final int g) {
        if (pill == null) {
            quitar();
            return;
        }
        pill.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                .setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) quitar();
            }
        }, 220);
    }

    /** Deslizar hacia arriba. */
    private void deslizarArriba() {
        h.removeCallbacksAndMessages(null);
        gen++;
        final int g = gen;
        cancelarAnimaciones();
        if (pill == null) return;
        pill.setTranslationY(0f);

        if (expanded && musicaActiva()) {
            // La musica expandida solo se encoge
            colapsar(g);
            return;
        }
        if (!expanded && transitorio == false && actual == musica) {
            // Capsula persistente: se oculta hasta la proxima cancion
            musicaSilenciada = true;
        }
        pill.animate().translationY(-dp(30)).alpha(0f).setDuration(160).start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) quitar();
            }
        }, 170);
    }

    private void tocar() {
        if (pill == null) return;
        if (!expanded) {
            if (!transitorio && actual == musica && musicaActiva()) {
                expandirMusica();
            }
            return;
        }
        PendingIntent p = actual != null ? actual.accion : null;
        boolean eraMusica = actual != null && actual.tipo == Evento.MUSICA;
        if (eraMusica && musicaActiva()) {
            h.removeCallbacksAndMessages(null);
            gen++;
            cancelarAnimaciones();
            colapsar(gen);
        } else {
            h.removeCallbacksAndMessages(null);
            gen++;
            final int g = gen;
            cancelarAnimaciones();
            pill.animate().translationY(-dp(30)).alpha(0f).setDuration(160).start();
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (g == gen) quitar();
                }
            }, 170);
        }
        if (p != null) {
            try {
                p.send();
            } catch (Throwable e) {
                XposedBridge.log(e);
            }
        }
    }

    private void quitarTodo() {
        h.removeCallbacksAndMessages(null);
        gen++;
        cancelarAnimaciones();
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
        cancelarAnimaciones();
        try {
            if (root != null) wm.removeViewImmediate(root);
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
        root = null;
        pill = null;
        ventanaGrande = false;
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
            w = expW + dp(40);
            hh = expTop + dp(IslandView.alturaMaxDp(cfg)) + dp(16);
        } else {
            w = compW + 2 * Math.abs(compTx) + dp(24);
            hh = compTop + compH + dp(8);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, hh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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

    private void construir(Evento e) {
        medir();
        root = new FrameLayout(ctx);
        pill = new IslandView(ctx, cfg, dividido ? cutW : 0);
        pill.configurar(e, false, false);
        cornerPx = compH / 2f;
        pill.setCorner(cornerPx);

        pill.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (pill == null) return false;
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = ev.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = ev.getRawY() - downY;
                        if (dy < 0) pill.setTranslationY(dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float total = ev.getRawY() - downY;
                        if (total < -dp(24)) {
                            deslizarArriba();
                        } else if (Math.abs(total) < dp(8)) {
                            tocar();
                        } else {
                            pill.animate().translationY(0f).setDuration(120).start();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        pill.animate().translationY(0f).setDuration(120).start();
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
            XposedBridge.log(t);
        }
        XposedBridge.log("[IslaSystemUI] ventana anadida (notch=" + cutW + "px, barra=" + sbPx
                + "px)");
    }

    private void animarRect(final int toW, final int toH, final int toTop, final float toTx,
                            final float toR, int ms, TimeInterpolator ip, final Runnable alTerminar,
                            final int g) {
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
        va.setInterpolator(ip);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (g != gen || pill == null) return;
                float f = (Float) a.getAnimatedValue();
                lp.width = Math.round(fromW + (toW - fromW) * f);
                lp.height = Math.round(fromH + (toH - fromH) * f);
                lp.topMargin = Math.round(fromTop + (toTop - fromTop) * f);
                pill.setLayoutParams(lp);
                pill.setPivotX(lp.width / 2f);
                pill.setTranslationX(fromTx + (toTx - fromTx) * f);
                cornerPx = Math.max(0f, fromR + (toR - fromR) * f);
                pill.setCorner(cornerPx);
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
        if (pill != null) pill.animate().cancel();
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
