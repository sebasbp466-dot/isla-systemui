package com.lancelot.island;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * La capsula de la isla. La usan SystemUI (isla real) y la app (vista previa).
 * Tiene dos estados: compacto (icono + titulo) y expandido (icono grande + titulo + texto).
 */
final class IslandView extends LinearLayout {

    /** Alto de la capsula compacta, en dp. */
    static final int COMPACT_H_DP = 34;

    private final IslaConfig cfg;
    private final float density;
    private final ImageView icon;
    private final TextView title;
    private final TextView body;
    private final LinearLayout textos;
    private final GradientDrawable base;
    private final GradientDrawable shine;
    private boolean hasBody;
    private boolean expanded;

    IslandView(Context ctx, IslaConfig cfg) {
        super(ctx);
        this.cfg = cfg;
        density = ctx.getResources().getDisplayMetrics().density;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        base = new GradientDrawable();
        if (cfg.style == 1) {
            // Cristal: fondo translucido, borde claro y un brillo suave arriba
            int a = Math.min(Color.alpha(cfg.bgColor), 0xCC);
            base.setColor((cfg.bgColor & 0x00FFFFFF) | (a << 24));
            base.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
            shine = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x45FFFFFF, 0x00FFFFFF});
            setBackground(new LayerDrawable(new Drawable[]{base, shine}));
        } else {
            // Hyper: negra solida
            base.setColor(cfg.bgColor);
            shine = null;
            setBackground(base);
        }

        icon = new ImageView(ctx);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        if (cfg.showIcon) {
            addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        }

        textos = new LinearLayout(ctx);
        textos.setOrientation(VERTICAL);
        textos.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        addView(textos, tlp);

        title = new TextView(ctx);
        title.setTextColor(cfg.titleColor);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textos.addView(title);

        body = new TextView(ctx);
        body.setTextColor(cfg.bodyColor);
        body.setSingleLine(true);
        body.setEllipsize(TextUtils.TruncateAt.END);
        textos.addView(body);

        setExpanded(true);
    }

    /** Radio de las esquinas en pixeles. */
    void setCorner(float px) {
        base.setCornerRadius(px);
        if (shine != null) shine.setCornerRadius(px);
    }

    boolean isExpanded() {
        return expanded;
    }

    void setExpanded(boolean e) {
        expanded = e;
        if (e) {
            setPadding(dp(12), dp(6), dp(18), dp(6));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        } else {
            setPadding(dp(8), dp(4), dp(12), dp(4));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
        if (cfg.showIcon) {
            int lado = e ? Math.max(24, cfg.heightDp - 24) : 22;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) icon.getLayoutParams();
            lp.width = dp(lado);
            lp.height = dp(lado);
            icon.setLayoutParams(lp);
        }
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) textos.getLayoutParams();
        tlp.setMarginStart(cfg.showIcon ? (e ? dp(12) : dp(8)) : dp(4));
        textos.setLayoutParams(tlp);
        body.setVisibility(e && hasBody ? View.VISIBLE : View.GONE);
    }

    void bind(CharSequence t, CharSequence b, Drawable ic) {
        title.setText(t);
        body.setText(b);
        hasBody = !TextUtils.isEmpty(b);
        body.setVisibility(expanded && hasBody ? View.VISIBLE : View.GONE);
        if (cfg.showIcon) {
            if (ic != null) {
                icon.setImageDrawable(ic);
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
