package com.lancelot.island;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import de.robv.android.xposed.XposedBridge;

/**
 * Dibuja la isla dentro de SystemUI, al estilo Hyper Island:
 * entra como capsula compacta, se expande con rebote, se queda un rato,
 * se encoge y sale. Se descarta deslizando hacia arriba y al tocarla abre la notificacion.
 * Las vistas solo existen mientras la isla esta visible.
 */
final class IslandController {

    private static final int HOLD_COMPACT_MS = 600;

    private static IslandController inst;

    static synchronized IslandController get(Context c) {
        if (inst == null) inst = new IslandController(c);
        return inst;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float density;

    private IslaConfig cfg = new IslaConfig();
    private FrameLayout root;
    private IslandView pill;
    private PendingIntent action;
    private boolean shown;
    private boolean expanded;
    private ValueAnimator sizeAnim;
    /** Cada isla/animacion nueva sube el contador; los pasos programados de una anterior se descartan. */
    private int gen;
    private float downY;

    private IslandController(Context c) {
        Context app = c.getApplicationContext();
        ctx = app != null ? app : c;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        density = ctx.getResources().getDisplayMetrics().density;
    }

    void show(final IslaConfig config, final CharSequence t, final CharSequence b,
              final Drawable ic, final PendingIntent pi) {
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mostrar(config, t, b, ic, pi);
                } catch (Throwable e) {
                    XposedBridge.log(e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ flujo

    private void mostrar(IslaConfig config, CharSequence t, CharSequence b, Drawable ic,
                         PendingIntent pi) {
        action = pi;
        h.removeCallbacksAndMessages(null);
        gen++;
        final int g = gen;
        cancelarAnimaciones();

        if (shown && pill != null) {
            // Ya hay una isla: se actualiza el contenido y se reinicia el tiempo
            pill.setAlpha(1f);
            pill.setScaleX(1f);
            pill.setScaleY(1f);
            pill.setTranslationY(0f);
            pill.bind(t, b, ic);
            if (expanded) {
                programarColapso(g);
            } else {
                expandir(g);
            }
            return;
        }

        cfg = config;
        construir();
        pill.bind(t, b, ic);
        wm.addView(root, params());
        shown = true;
        expanded = false;
        XposedBridge.log("[IslaSystemUI] ventana anadida");
        entrar(g);
    }

    private void entrar(final int g) {
        pill.setAlpha(0f);
        pill.setScaleX(0.6f);
        pill.setScaleY(0.6f);
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) expandir(g);
            }
        }, HOLD_COMPACT_MS);
    }

    private void expandir(int g) {
        if (pill == null) return;
        expanded = true;
        pill.setExpanded(true);
        int w = dp(cfg.widthDp);
        int hh = dp(cfg.heightDp);
        TimeInterpolator ip = cfg.bounce ? new OvershootInterpolator(0.9f)
                : new DecelerateInterpolator();
        animarTamano(w, hh, Math.min(dp(cfg.radiusDp), hh / 2f), 380, ip, null, g);
        programarColapso(g);
    }

    private void programarColapso(final int g) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) colapsar(g);
            }
        }, cfg.durationMs);
    }

    private void colapsar(final int g) {
        if (pill == null) return;
        int ch = dp(IslandView.COMPACT_H_DP);
        animarTamano(dp(cfg.compactW), ch, ch / 2f, 260, new DecelerateInterpolator(),
                new Runnable() {
                    @Override
                    public void run() {
                        if (g != gen || pill == null) return;
                        expanded = false;
                        pill.setExpanded(false);
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (g == gen) salir(g);
                            }
                        }, 450);
                    }
                }, g);
    }

    private void salir(final int g) {
        if (pill == null) return;
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

    /** Descarta la isla deslizando hacia arriba o despues de tocarla. */
    private void descartar() {
        h.removeCallbacksAndMessages(null);
        gen++;
        final int g = gen;
        cancelarAnimaciones();
        if (pill == null) return;
        pill.animate().translationY(-dp(30)).alpha(0f).setDuration(160).start();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (g == gen) quitar();
            }
        }, 170);
    }

    private void quitar() {
        if (!shown) return;
        shown = false;
        expanded = false;
        cancelarAnimaciones();
        try {
            if (root != null) wm.removeViewImmediate(root);
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
        root = null;
        pill = null;
    }

    // ------------------------------------------------------------------ vistas y animacion

    private void construir() {
        root = new FrameLayout(ctx);
        pill = new IslandView(ctx, cfg);
        pill.setExpanded(false);
        int ch = dp(IslandView.COMPACT_H_DP);
        pill.setCorner(ch / 2f);

        pill.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (pill == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - downY;
                        if (dy < 0) pill.setTranslationY(dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float total = e.getRawY() - downY;
                        if (total < -dp(24)) {
                            descartar();
                        } else if (Math.abs(total) < dp(8)) {
                            abrir();
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
                dp(cfg.compactW), ch, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.topMargin = dp(cfg.topDp);
        root.addView(pill, plp);
        pill.setPivotX(dp(cfg.compactW) / 2f);
        pill.setPivotY(0f);
    }

    private void abrir() {
        PendingIntent p = action;
        descartar();
        if (p != null) {
            try {
                p.send();
            } catch (Throwable e) {
                XposedBridge.log(e);
            }
        }
    }

    private void animarTamano(final int toW, final int toH, final float toR, int ms,
                              TimeInterpolator ip, final Runnable alTerminar, final int g) {
        if (pill == null) return;
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        final int fromW = lp.width;
        final int fromH = lp.height;
        final float fromR = Math.min(dp(cfg.radiusDp), fromH / 2f);

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
                pill.setLayoutParams(lp);
                pill.setPivotX(lp.width / 2f);
                pill.setCorner(Math.max(0f, fromR + (toR - fromR) * f));
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelada;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelada = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelada || g != gen || pill == null) return;
                lp.width = toW;
                lp.height = toH;
                pill.setLayoutParams(lp);
                pill.setPivotX(toW / 2f);
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

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(cfg.widthDp + 40),
                dp(cfg.heightDp + cfg.topDp + 24),
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

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
