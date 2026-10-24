package com.lancelot.island;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** La pastilla de la isla. La usan SystemUI (isla real) y la app (vista previa). */
final class IslandView extends LinearLayout {

    private final ImageView icon;
    private final TextView title;
    private final TextView body;
    private final boolean showIcon;
    private final float density;

    IslandView(Context ctx, IslaConfig cfg) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        showIcon = cfg.showIcon;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(12), dp(6), dp(18), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cfg.bgColor);
        bg.setCornerRadius(dp(Math.min(cfg.radiusDp, cfg.heightDp / 2)));
        setBackground(bg);

        int lado = Math.max(24, cfg.heightDp - 24);
        icon = new ImageView(ctx);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        if (showIcon) {
            addView(icon, new LinearLayout.LayoutParams(dp(lado), dp(lado)));
        }

        LinearLayout textos = new LinearLayout(ctx);
        textos.setOrientation(VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(showIcon ? dp(12) : dp(6));
        addView(textos, tlp);

        title = new TextView(ctx);
        title.setTextColor(cfg.titleColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textos.addView(title);

        body = new TextView(ctx);
        body.setTextColor(cfg.bodyColor);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setSingleLine(true);
        body.setEllipsize(TextUtils.TruncateAt.END);
        textos.addView(body);
    }

    void bind(CharSequence t, CharSequence b, Drawable ic) {
        title.setText(t);
        body.setText(b);
        body.setVisibility(TextUtils.isEmpty(b) ? View.GONE : View.VISIBLE);
        if (showIcon) {
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
