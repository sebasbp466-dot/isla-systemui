package com.lancelot.island;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import de.robv.android.xposed.XposedBridge;

/**
 * Dibuja la isla dentro de SystemUI. Las vistas solo existen mientras la isla
 * esta visible: al terminar se quitan de la ventana y se sueltan.
 */
final class IslandController {

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
    private boolean exiting;

    private final Runnable hideRun = new Runnable() {
        @Override
        public void run() {
            animateOut();
        }
    };

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
                    action = pi;
                    h.removeCallbacks(hideRun);
                    exiting = false;

                    if (shown && pill != null) {
                        // Ya hay una isla en pantalla: solo cambia el contenido
                        pill.animate().cancel();
                        pill.bind(t, b, ic);
                        pill.setScaleX(1f);
                        pill.setScaleY(1f);
                        pill.setAlpha(1f);
                    } else {
                        cfg = config;
                        construir();
                        pill.bind(t, b, ic);
                        wm.addView(root, params());
                        shown = true;
                        XposedBridge.log("[IslaSystemUI] ventana anadida");
                        pill.setScaleX(0.28f);
                        pill.setScaleY(0.5f);
                        pill.setAlpha(0f);
                        pill.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(350)
                                .setInterpolator(cfg.bounce
                                        ? new OvershootInterpolator(0.9f)
                                        : new DecelerateInterpolator())
                                .start();
                    }
                    h.postDelayed(hideRun, cfg.durationMs);
                } catch (Throwable e) {
                    XposedBridge.log(e);
                }
            }
        });
    }

    private void construir() {
        root = new FrameLayout(ctx);
        pill = new IslandView(ctx, cfg);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PendingIntent p = action;
                hideNow();
                if (p != null) {
                    try {
                        p.send();
                    } catch (Throwable e) {
                        XposedBridge.log(e);
                    }
                }
            }
        });
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                dp(cfg.widthDp), dp(cfg.heightDp), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.topMargin = dp(cfg.topDp);
        root.addView(pill, plp);
        pill.setPivotX(dp(cfg.widthDp) / 2f);
        pill.setPivotY(0f);
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(cfg.widthDp + 20),
                dp(cfg.heightDp + cfg.topDp + 10),
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

    private void animateOut() {
        if (!shown || pill == null) return;
        exiting = true;
        pill.animate().cancel();
        pill.animate().scaleX(0.28f).scaleY(0.5f).alpha(0f)
                .setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        removeNow();
                    }
                })
                .start();
    }

    private void hideNow() {
        h.removeCallbacks(hideRun);
        exiting = true;
        if (pill != null) pill.animate().cancel();
        removeNow();
    }

    private void removeNow() {
        // Si llego una notificacion nueva en medio de la salida, exiting vuelve a false y no se quita
        if (!shown || !exiting) return;
        shown = false;
        exiting = false;
        try {
            wm.removeViewImmediate(root);
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
        root = null;
        pill = null;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
