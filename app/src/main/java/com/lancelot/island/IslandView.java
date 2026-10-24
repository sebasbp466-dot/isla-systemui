package com.lancelot.island;

import android.animation.ValueAnimator;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * La capsula de la isla. La usan SystemUI (isla real) y la app (vista previa).
 * Estado compacto: icono a un lado, indicador al otro y, si hay notch, un hueco en medio.
 * Estado expandido: icono grande, textos, indicador y, segun el evento, barra y controles.
 */
final class IslandView extends LinearLayout {

    /** Alto extra que necesita cada tipo de evento al expandirse, en dp. */
    static int alturaDp(IslaConfig cfg, Evento e) {
        int h = cfg.heightDp;
        if (e != null) {
            if (e.tipo == Evento.MUSICA) {
                h += 54;
            } else if (e.tipo == Evento.CARGA) {
                h += 14;
            }
        }
        return h;
    }

    /** Alto extra maximo (musica), para dimensionar la ventana. */
    static int alturaMaxDp(IslaConfig cfg) {
        return cfg.heightDp + 54;
    }

    private final IslaConfig cfg;
    private final float density;
    /** Ancho del notch en px; si es mayor que 0 la compacta deja un hueco en medio. */
    private final int cutoutPx;

    private final LinearLayout fila;
    private final FrameLayout iconoFrame;
    private final ImageView icono;
    private final GlyphView glifo;
    private final View hueco;
    private final LinearLayout textos;
    private final TextView titulo;
    private final TextView cuerpo;
    private final FrameLayout derecha;
    private final EqualizerView ecual;
    private final TextView valor;
    private final LinearLayout inferior;
    private final BarraView barra;
    private final LinearLayout controles;
    private final GlyphView bAnterior;
    private final GlyphView bPlay;
    private final GlyphView bSiguiente;

    private final GradientDrawable base;
    private final GradientDrawable shine;

    private ValueAnimator contador;
    private Evento ev;
    private boolean expandido;

    IslandView(Context ctx, IslaConfig cfg, int cutoutPx) {
        super(ctx);
        this.cfg = cfg;
        this.cutoutPx = cutoutPx;
        density = ctx.getResources().getDisplayMetrics().density;

        setOrientation(VERTICAL);
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
            base.setColor(cfg.bgColor);
            shine = null;
            setBackground(base);
        }

        // ---- fila principal
        fila = new LinearLayout(ctx);
        fila.setOrientation(HORIZONTAL);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        addView(fila, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        iconoFrame = new FrameLayout(ctx);
        icono = new ImageView(ctx);
        icono.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconoFrame.addView(icono, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        iconoFrame.setClipToOutline(true);
        iconoFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                // Esquinas a un cuarto del tamaño, como en la especificacion de Xiaomi
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getWidth() / 4f);
            }
        });
        glifo = new GlyphView(ctx);
        iconoFrame.addView(glifo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fila.addView(iconoFrame, new LinearLayout.LayoutParams(dp(22), dp(22)));

        hueco = new View(ctx);
        fila.addView(hueco, new LinearLayout.LayoutParams(0, 1, 1f));

        textos = new LinearLayout(ctx);
        textos.setOrientation(VERTICAL);
        textos.setGravity(Gravity.CENTER_VERTICAL);
        fila.addView(textos, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        titulo = new TextView(ctx);
        titulo.setTextColor(cfg.titleColor);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        titulo.setSingleLine(true);
        titulo.setEllipsize(TextUtils.TruncateAt.END);
        textos.addView(titulo);

        cuerpo = new TextView(ctx);
        cuerpo.setTextColor(cfg.bodyColor);
        cuerpo.setSingleLine(true);
        cuerpo.setEllipsize(TextUtils.TruncateAt.END);
        cuerpo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textos.addView(cuerpo);

        derecha = new FrameLayout(ctx);
        ecual = new EqualizerView(ctx);
        derecha.addView(ecual, new FrameLayout.LayoutParams(dp(18), dp(14), Gravity.CENTER));
        valor = new TextView(ctx);
        valor.setTypeface(Typeface.DEFAULT_BOLD);
        valor.setSingleLine(true);
        derecha.addView(valor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        fila.addView(derecha, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        derecha.setVisibility(GONE);

        // ---- parte inferior (musica y carga)
        inferior = new LinearLayout(ctx);
        inferior.setOrientation(VERTICAL);
        inferior.setVisibility(GONE);
        addView(inferior, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        barra = new BarraView(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(4));
        blp.topMargin = dp(10);
        inferior.addView(barra, blp);

        controles = new LinearLayout(ctx);
        controles.setOrientation(HORIZONTAL);
        controles.setGravity(Gravity.CENTER);
        inferior.addView(controles, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        bAnterior = boton();
        bPlay = boton();
        bSiguiente = boton();
        controles.addView(bAnterior, botonLp());
        controles.addView(bPlay, botonLp());
        controles.addView(bSiguiente, botonLp());

        configurar(null, true, false);
    }

    private GlyphView boton() {
        GlyphView g = new GlyphView(getContext());
        g.setPadding(dp(8), dp(8), dp(8), dp(8));
        return g;
    }

    private LinearLayout.LayoutParams botonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(36));
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        lp.topMargin = dp(4);
        return lp;
    }

    /** Radio de las esquinas en pixeles. */
    void setCorner(float px) {
        base.setCornerRadius(px);
        if (shine != null) shine.setCornerRadius(px);
    }

    boolean isExpandido() {
        return expandido;
    }

    /**
     * Muestra el evento en el estado pedido.
     * animar: lanza las animaciones de entrada (icono, contador, barra).
     */
    void configurar(final Evento e, boolean ex, boolean animar) {
        ev = e;
        expandido = ex;
        if (contador != null) {
            contador.cancel();
            contador = null;
        }
        final boolean dividido = !ex && cutoutPx > 0;

        // ---- espacios
        if (ex) {
            setPadding(dp(14), dp(10), dp(16), dp(10));
        } else {
            setPadding(dp(dividido ? 10 : 9), 0, dp(10), 0);
        }

        if (e == null) {
            iconoFrame.setVisibility(GONE);
            textos.setVisibility(GONE);
            hueco.setVisibility(GONE);
            derecha.setVisibility(GONE);
            inferior.setVisibility(GONE);
            return;
        }

        // ---- icono o glifo
        boolean usaGlifo = e.glifo != 0 || (e.icono == null && e.tipo == Evento.MUSICA);
        boolean conIcono = usaGlifo || cfg.showIcon;
        iconoFrame.setVisibility(conIcono ? VISIBLE : GONE);
        int lado = ex ? Math.max(dp(32), dp(cfg.heightDp - 28)) : dp(dividido ? 20 : 22);
        LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) iconoFrame.getLayoutParams();
        ilp.width = lado;
        ilp.height = lado;
        iconoFrame.setLayoutParams(ilp);
        if (usaGlifo) {
            icono.setVisibility(GONE);
            glifo.setVisibility(VISIBLE);
            int tipoGlifo = e.glifo != 0 ? e.glifo : GlyphView.NOTA;
            glifo.set(tipoGlifo, e.nivel < 0 ? 0f : e.nivel / 100f, e.activo, e.sub, e.color);
            if (ex && animar) glifo.entrada();
        } else {
            glifo.setVisibility(GONE);
            icono.setVisibility(VISIBLE);
            icono.setImageDrawable(e.icono);
        }

        // ---- textos o hueco para el notch
        textos.setVisibility(dividido ? GONE : VISIBLE);
        hueco.setVisibility(dividido ? VISIBLE : GONE);
        if (dividido) {
            LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) hueco.getLayoutParams();
            hlp.width = 0;
            hueco.setLayoutParams(hlp);
        }
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) textos.getLayoutParams();
        tlp.setMarginStart(conIcono ? (ex ? dp(12) : dp(8)) : dp(4));
        textos.setLayoutParams(tlp);
        titulo.setText(e.titulo);
        titulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, ex ? 14 : 12);
        cuerpo.setText(e.texto);
        cuerpo.setVisibility(ex && !TextUtils.isEmpty(e.texto) ? VISIBLE : GONE);

        // ---- indicador de la derecha
        if (e.tipo == Evento.MUSICA) {
            derecha.setVisibility(VISIBLE);
            valor.setVisibility(GONE);
            ecual.setVisibility(VISIBLE);
            ecual.setColor(e.color);
            ecual.setAnimando(e.reproduciendo);
            FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) ecual.getLayoutParams();
            elp.width = ex ? dp(24) : dp(18);
            elp.height = ex ? dp(20) : dp(14);
            ecual.setLayoutParams(elp);
        } else if ((e.tipo == Evento.CARGA || e.tipo == Evento.BATERIA) && e.nivel >= 0) {
            derecha.setVisibility(VISIBLE);
            ecual.setAnimando(false);
            ecual.setVisibility(GONE);
            valor.setVisibility(VISIBLE);
            valor.setTextColor(e.color);
            valor.setTextSize(TypedValue.COMPLEX_UNIT_SP, ex ? 20 : 11);
            if (ex && animar && e.nivel > 0) {
                final int destino = e.nivel;
                contador = ValueAnimator.ofInt(0, destino);
                contador.setDuration(750);
                contador.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        valor.setText(((Integer) a.getAnimatedValue()) + "%");
                    }
                });
                valor.setText("0%");
                contador.start();
            } else {
                valor.setText(e.nivel + "%");
            }
        } else {
            ecual.setAnimando(false);
            derecha.setVisibility(GONE);
        }

        // ---- parte inferior
        if (ex && e.tipo == Evento.MUSICA) {
            inferior.setVisibility(VISIBLE);
            controles.setVisibility(VISIBLE);
            barra.setColores(0xFFFFFFFF, 0x33FFFFFF);
            if (e.duracion > 0) {
                float f = e.posicionAhora() / (float) e.duracion;
                if (e.reproduciendo) {
                    barra.reproducir(f, e.duracion - e.posicionAhora());
                } else {
                    barra.setProgreso(f);
                }
            } else {
                barra.setProgreso(0f);
            }
            bAnterior.set(GlyphView.ANTERIOR, 0f, false, 0, 0xFFFFFFFF);
            bPlay.set(e.reproduciendo ? GlyphView.PAUSA : GlyphView.PLAY, 0f, false, 0,
                    0xFFFFFFFF);
            bSiguiente.set(GlyphView.SIGUIENTE, 0f, false, 0, 0xFFFFFFFF);
            bAnterior.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (ev != null) ev.anterior();
                }
            });
            bPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (ev != null) ev.alternar();
                }
            });
            bSiguiente.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (ev != null) ev.siguiente();
                }
            });
        } else if (ex && e.tipo == Evento.CARGA) {
            inferior.setVisibility(VISIBLE);
            controles.setVisibility(GONE);
            barra.setColores(e.color, 0x33FFFFFF);
            if (animar) {
                barra.setProgreso(0f);
                barra.animarA(Math.max(0, e.nivel) / 100f, 750);
            } else {
                barra.setProgreso(Math.max(0, e.nivel) / 100f);
            }
        } else {
            inferior.setVisibility(GONE);
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
