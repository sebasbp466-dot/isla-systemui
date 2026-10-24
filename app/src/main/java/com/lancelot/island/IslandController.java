package com.lancelot.island;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * Dibuja la isla. Solo existe en el WindowManager mientras se muestra:
 * al terminar se quita la ventana y no queda nada residente salvo unas pocas vistas.
 */
final class IslandController {

    // ---- Ajustes faciles de tocar ----
    private static final int PILL_W_DP = 320;   // ancho de la isla
    private static final int PILL_H_DP = 64;    // alto de la isla
    private static final int TOP_DP = 6;        // separacion con el borde superior
    private static final int SHOW_MS = 4500;    // cuanto tiempo se queda visible
    // -----------------------------------

    private static IslandController inst;

    static synchronized IslandController get(Context c) {
        if (inst == null) inst = new IslandController(c);
        return inst;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float density;

    private FrameLayout root;
    private LinearLayout pill;
    private ImageView icon;
    private TextView title;
    private TextView body;
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

    void show(final CharSequence t, final CharSequence b, final Drawable ic, final PendingIntent pi) {
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    build();
                    action = pi;
                    title.setText(t);
                    body.setText(b);
                    body.setVisibility(TextUtils.isEmpty(b) ? View.GONE : View.VISIBLE);
                    if (ic != null) {
                        icon.setImageDrawable(ic);
                        icon.setVisibility(View.VISIBLE);
                    } else {
                        icon.setVisibility(View.GONE);
                    }

                    h.removeCallbacks(hideRun);
                    exiting = false;
                    pill.animate().cancel();

                    if (!shown) {
                        wm.addView(root, params());
                        shown = true;
                        pill.setScaleX(0.28f);
                        pill.setScaleY(0.5f);
                        pill.setAlpha(0f);
                        pill.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(350)
                                .setInterpolator(new OvershootInterpolator(0.9f))
                                .start();
                    } else {
                        pill.setScaleX(1f);
                        pill.setScaleY(1f);
                        pill.setAlpha(1f);
                    }
                    h.postDelayed(hideRun, SHOW_MS);
                } catch (Throwable e) {
                    XposedBridge.log(e);
                }
            }
        });
    }

    private void build() {
        if (root != null) return;

        root = new FrameLayout(ctx);

        pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(8), dp(18), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(dp(PILL_H_DP / 2));
        pill.setBackground(bg);
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

        icon = new ImageView(ctx);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        pill.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout textos = new LinearLayout(ctx);
        textos.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        pill.addView(textos, tlp);

        title = new TextView(ctx);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textos.addView(title);

        body = new TextView(ctx);
        body.setTextColor(0xFFCCCCCC);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setSingleLine(true);
        body.setEllipsize(TextUtils.TruncateAt.END);
        textos.addView(body);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(PILL_W_DP), dp(PILL_H_DP),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.topMargin = dp(TOP_DP);
        root.addView(pill, plp);

        pill.setPivotX(dp(PILL_W_DP) / 2f);
        pill.setPivotY(0f);
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(PILL_W_DP + 20),
                dp(PILL_H_DP + TOP_DP + 10),
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
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
