package com.lancelot.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * La capsula de la isla. La usan SystemUI (isla real) y la app (vista previa).
 * Estado compacto: icono a un lado, indicador al otro y, si hay notch, un hueco en medio.
 * Estado expandido: icono grande, textos, indicador y, segun el evento, barra, controles o botones.
 */
final class IslandView extends LinearLayout {

    /** Alto extra que necesita cada tipo de evento al expandirse, en dp. */
    static int alturaDp(IslaConfig cfg, Evento e) {
        int h = cfg.heightDp;
        if (e != null) {
            if (e.tipo == Evento.MUSICA) {
                h += 64;
            } else if (e.tipo == Evento.CARGA || e.tipo == Evento.PROGRESO
                    || e.tipo == Evento.VOLUMEN) {
                h += 14;
            }
            if (e.botonTxt != null && e.botonTxt.length > 0) h += 46;
            if (e.tipo == Evento.NOTIF && e.app != null && e.app.length() > 0) h += 8;
        }
        return h;
    }

    /** Alto extra maximo, para dimensionar la ventana. */
    static int alturaMaxDp(IslaConfig cfg) {
        return cfg.heightDp + 64 + 8;
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
    private final TextView leyenda;
    private final ImageView miniatura;
    private final TextView cuerpo;
    private final FrameLayout derecha;
    private final EqualizerView ecual;
    private final TextView valor;
    private final Chronometer crono;
    private final AnilloView anillo;
    private final LinearLayout inferior;
    private final FrameLayout barraFrame;
    private final BarraView barra;
    private final LinearLayout controles;
    private final GlyphView bAnterior;
    private final GlyphView bPlay;
    private final GlyphView bSiguiente;
    private final LinearLayout botones;

    private final FondoDrawable fondo;
    private Drawable blur;
    private java.lang.reflect.Method mBlurRadio;
    private java.lang.reflect.Method mBlurEsquina;
    private String estadoBlur = "sin intentar";
    private float esquina;

    private ValueAnimator contador;
    private ValueAnimator luzAnim;
    private ValueAnimator auraAnim;
    private int auraColor;
    private Evento ev;
    private boolean expandido;
    private String ultTitulo;
    private String ultTexto;
    private Object botonesDe;

    /** Se avisa cuando el usuario toca un control interno (reinicia el temporizador). */
    private Runnable alInteraccion;
    /** Se avisa despues de pulsar un boton (el controlador cierra la isla). */
    private Runnable alBoton;

    IslandView(Context ctx, IslaConfig cfg, int cutoutPx) {
        super(ctx);
        this.cfg = cfg;
        this.cutoutPx = cutoutPx;
        density = ctx.getResources().getDisplayMetrics().density;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);

        fondo = new FondoDrawable(cfg.style, cfg.bgColor, density);
        setBackground(fondo);

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
                // Hyper: esquinas a un cuarto del tamaño (especificacion de Xiaomi).
                // Cristal: circulo, como una lente.
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getWidth() / (cfg.style == 1 || cfg.style == 4 ? 2f : cfg.style == 2 ? 3f : 4f));
            }
        });
        glifo = new GlyphView(ctx);
        iconoFrame.addView(glifo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fila.addView(iconoFrame, new LinearLayout.LayoutParams(dp(22), dp(22)));

        hueco = new View(ctx);

        textos = new LinearLayout(ctx);
        textos.setOrientation(VERTICAL);
        textos.setGravity(Gravity.CENTER_VERTICAL);
        fila.addView(textos, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        fila.addView(hueco, new LinearLayout.LayoutParams(0, 1, 1f));

        leyenda = new TextView(ctx);
        leyenda.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        leyenda.setAllCaps(true);
        leyenda.setLetterSpacing(0.08f);
        leyenda.setSingleLine(true);
        leyenda.setEllipsize(TextUtils.TruncateAt.END);
        leyenda.setVisibility(GONE);
        textos.addView(leyenda);

        titulo = new TextView(ctx);
        titulo.setTextColor(cfg.titleColor);
        titulo.setTypeface(Typeface.create(cfg.style == 1 || cfg.style == 4 ? "sans-serif"
                : "sans-serif-medium",
                Typeface.BOLD));
        titulo.setSingleLine(true);
        titulo.setEllipsize(TextUtils.TruncateAt.END);
        titulo.setMarqueeRepeatLimit(1);
        textos.addView(titulo);

        cuerpo = new TextView(ctx);
        cuerpo.setTextColor(cfg.bodyColor);
        cuerpo.setSingleLine(true);
        cuerpo.setEllipsize(TextUtils.TruncateAt.END);
        cuerpo.setMarqueeRepeatLimit(1);
        cuerpo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textos.addView(cuerpo);

        miniatura = new ImageView(ctx);
        miniatura.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniatura.setClipToOutline(true);
        miniatura.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        miniatura.setVisibility(GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        mlp.setMarginStart(dp(8));
        fila.addView(miniatura, mlp);

        derecha = new FrameLayout(ctx);
        ecual = new EqualizerView(ctx);
        derecha.addView(ecual, new FrameLayout.LayoutParams(dp(18), dp(14), Gravity.CENTER));
        valor = new TextView(ctx);
        valor.setTypeface(cfg.style == 2 ? Typeface.MONOSPACE : Typeface.create(
                cfg.style == 1 ? "sans-serif-light" : "sans-serif-condensed", Typeface.BOLD));
        valor.setSingleLine(true);
        derecha.addView(valor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        crono = new Chronometer(ctx);
        crono.setTypeface(cfg.style == 2 ? Typeface.MONOSPACE : Typeface.create(
                cfg.style == 1 ? "sans-serif-light" : "sans-serif-condensed", Typeface.BOLD));
        crono.setSingleLine(true);
        derecha.addView(crono, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        anillo = new AnilloView(ctx);
        derecha.addView(anillo, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        fila.addView(derecha, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        derecha.setVisibility(GONE);

        // ---- parte inferior (musica, carga, progreso, botones)
        inferior = new LinearLayout(ctx);
        inferior.setOrientation(VERTICAL);
        inferior.setVisibility(GONE);
        addView(inferior, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // La barra es fina, pero su zona tactil (para buscar en la cancion) es mas alta
        barraFrame = new FrameLayout(ctx);
        barra = new BarraView(ctx);
        barraFrame.addView(barra, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(4), Gravity.CENTER_VERTICAL));
        inferior.addView(barraFrame, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(4)));

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

        botones = new LinearLayout(ctx);
        botones.setOrientation(HORIZONTAL);
        botones.setGravity(Gravity.CENTER);
        botones.setVisibility(GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(36));
        bl.topMargin = dp(10);
        inferior.addView(botones, bl);

        // Controles de musica: cada pulsacion reinicia el temporizador de la isla
        bAnterior.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ev != null) ev.anterior();
                interaccion();
            }
        });
        bPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ev != null) ev.alternar();
                interaccion();
            }
        });
        bSiguiente.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ev != null) ev.siguiente();
                interaccion();
            }
        });
        barraFrame.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent m) {
                return buscar(v, m);
            }
        });

        configurar(null, true, false);
    }

    void setAlInteraccion(Runnable r) {
        alInteraccion = r;
    }

    void setAlBoton(Runnable r) {
        alBoton = r;
    }

    private void interaccion() {
        if (alInteraccion != null) alInteraccion.run();
    }

    /** Arrastrar sobre la barra de la musica para saltar a otro punto. */
    private boolean buscar(View v, MotionEvent m) {
        Evento e = ev;
        if (e == null || e.tipo != Evento.MUSICA || e.duracion <= 0 || v.getWidth() <= 0) {
            return false;
        }
        float f = Math.max(0f, Math.min(1f, m.getX() / v.getWidth()));
        switch (m.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                interaccion();
                barra.setProgreso(f);
                return true;
            case MotionEvent.ACTION_MOVE:
                interaccion();
                barra.setProgreso(f);
                return true;
            case MotionEvent.ACTION_UP:
                long destino = (long) (f * e.duracion);
                e.buscar(destino);
                e.posicion = destino;
                e.marca = SystemClock.elapsedRealtime();
                if (e.reproduciendo) {
                    barra.reproducir(f, e.duracion - destino);
                } else {
                    barra.setProgreso(f);
                }
                interaccion();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
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
        esquina = px;
        fondo.setRadio(px);
        if (blur != null && mBlurEsquina != null) {
            try {
                mBlurEsquina.invoke(blur, px);
            } catch (Throwable t) {
                mBlurEsquina = null;
            }
        }
    }

    /** Resultado del intento de desenfoque, para el registro de LSPosed. */
    String estadoBlur() {
        return estadoBlur;
    }

    /**
     * Desenfoque real del fondo (estilo Cristal). Usa el mismo mecanismo que la propia ventana
     * del sistema; si la ROM no lo permite se queda el cristal translucido sin desenfoque.
     * Hay que llamarlo con la vista ya añadida a la ventana.
     */
    private boolean blurPendiente;

    void activarBlur() {
        if (cfg.style != 1 || cfg.blurDp <= 0 || blur != null) {
            if (cfg.style == 1 && cfg.blurDp <= 0) estadoBlur = "desactivado en ajustes";
            return;
        }
        try {
            java.lang.reflect.Method gv = View.class.getDeclaredMethod("getViewRootImpl");
            gv.setAccessible(true);
            Object vri = gv.invoke(this);
            if (vri == null) {
                estadoBlur = "esperando a que la vista este en la ventana";
                if (!blurPendiente) {
                    blurPendiente = true;
                    addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                            removeOnAttachStateChangeListener(this);
                            blurPendiente = false;
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    activarBlur();
                                }
                            });
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {
                        }
                    });
                }
                return;
            }
            java.lang.reflect.Method crear = vri.getClass().getDeclaredMethod(
                    "createBackgroundBlurDrawable");
            crear.setAccessible(true);
            Drawable d = (Drawable) crear.invoke(vri);
            if (d == null) {
                estadoBlur = "la ROM no crea el desenfoque";
                return;
            }
            java.lang.reflect.Method radio = d.getClass().getDeclaredMethod("setBlurRadius",
                    int.class);
            radio.setAccessible(true);
            radio.invoke(d, dp(cfg.blurDp));
            java.lang.reflect.Method esq = null;
            try {
                esq = d.getClass().getDeclaredMethod("setCornerRadius", float.class);
                esq.setAccessible(true);
                esq.invoke(d, esquina);
            } catch (Throwable t) {
                esq = null;
            }
            mBlurRadio = radio;
            mBlurEsquina = esq;
            blur = d;
            fondo.setConBlur(true);
            setBackground(new LayerDrawable(new Drawable[]{d, fondo}));
            estadoBlur = "activo (" + cfg.blurDp + " dp)";
            de.robv.android.xposed.XposedBridge.log("[IslaSystemUI] blur=" + estadoBlur);
        } catch (Throwable t) {
            blur = null;
            estadoBlur = "no disponible: " + t.getClass().getSimpleName();
            de.robv.android.xposed.XposedBridge.log("[IslaSystemUI] blur=" + estadoBlur + " " + t);
        }
    }

    /** Cristal: el desenfoque pasa de 0 al valor elegido, como si la lente enfocara. */
    void enfocar(int ms) {
        if (blur == null || mBlurRadio == null || cfg.blurDp <= 0) return;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(ms);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                try {
                    float f = (Float) a.getAnimatedValue();
                    mBlurRadio.invoke(blur, Math.round(dp(cfg.blurDp) * f));
                } catch (Throwable t) {
                    // sin desenfoque animado, se queda el fijo
                }
            }
        });
        va.start();
    }

    /** Neon: parpadeo de tubo que se enciende. */
    void parpadeo() {
        final float[] pasos = {0f, 1f, 0.35f, 1f, 0.6f, 1f};
        ValueAnimator va = ValueAnimator.ofFloat(0f, pasos.length - 1f);
        va.setDuration(cfg.ms(340));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float v = (Float) a.getAnimatedValue();
                int i = Math.min(pasos.length - 2, (int) v);
                float t = v - i;
                setAlpha(pasos[i] + (pasos[i + 1] - pasos[i]) * t);
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                setAlpha(1f);
            }
        });
        va.start();
    }

    /** true si la barra de estado esta oculta (video, juegos); false si no se puede saber. */
    boolean pantallaCompleta() {
        try {
            android.view.WindowInsets wi = getRootWindowInsets();
            if (wi == null) return false;
            boolean vis = wi.isVisible(android.view.WindowInsets.Type.statusBars());
            de.robv.android.xposed.XposedBridge.log("[IslaSystemUI] barra visible=" + vis);
            return !vis;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean respondiendoVista;

    interface AlResponder {
        void enviar(String texto);

        void cancelar();
    }

    /** Cambia los botones por un campo de texto para contestar. Devuelve el campo. */
    android.widget.EditText mostrarRespuesta(String pista, final AlResponder cb) {
        respondiendoVista = true;
        botonesDe = null;
        botones.removeAllViews();
        botones.setVisibility(VISIBLE);
        final android.widget.EditText et = new android.widget.EditText(getContext());
        et.setSingleLine(true);
        et.setHint(pista);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0x99FFFFFF);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        et.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable fe = new GradientDrawable();
        fe.setColor(0x33FFFFFF);
        fe.setCornerRadius(dp(18));
        et.setBackground(fe);
        LinearLayout.LayoutParams lpe = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT,
                1f);
        botones.addView(et, lpe);
        et.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                cb.enviar(String.valueOf(et.getText()));
                return true;
            }
        });
        TextView env = new TextView(getContext());
        env.setText("Enviar");
        env.setTextColor(Color.WHITE);
        env.setTypeface(Typeface.DEFAULT_BOLD);
        env.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        env.setGravity(Gravity.CENTER);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(0xFF2F7DF6);
        fb.setCornerRadius(dp(18));
        env.setBackground(fb);
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(dp(72), LayoutParams.MATCH_PARENT);
        lpb.setMarginStart(dp(6));
        botones.addView(env, lpb);
        env.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.enviar(String.valueOf(et.getText()));
            }
        });
        TextView x = new TextView(getContext());
        x.setText("\u2715");
        x.setTextColor(0xCCFFFFFF);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        x.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpx = new LinearLayout.LayoutParams(dp(36), LayoutParams.MATCH_PARENT);
        botones.addView(x, lpx);
        x.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.cancelar();
            }
        });
        return et;
    }

    void ocultarRespuesta() {
        respondiendoVista = false;
        botonesDe = null;
        if (ev != null && ev.botonTxt != null && ev.botonTxt.length > 0 && expandido) {
            construirBotones(ev);
        }
    }

    /** Xiaomi y iPhone muestran los avisos del sistema como un banner de una sola fila. */
    static boolean esBanner(IslaConfig cfg, Evento e) {
        if (cfg.style < 3 || e == null) return false;
        switch (e.tipo) {
            case Evento.CARGA:
            case Evento.TIMBRE:
            case Evento.AURIC:
            case Evento.BATERIA:
            case Evento.AJUSTE:
            case Evento.VOLUMEN:
            case Evento.PRIVACIDAD:
            case Evento.PORTAPAPELES:
                return true;
            default:
                return false;
        }
    }

    private ValueAnimator difAnim;

    /** Desenfoca el contenido (no el fondo) y lo va aclarando, como en Hyper Island. */
    void difuminar(float de, final float a, int ms) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        if (difAnim != null) difAnim.cancel();
        difAnim = ValueAnimator.ofFloat(de, a);
        difAnim.setDuration(ms);
        difAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        difAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                aplicarDifuminado((Float) va.getAnimatedValue());
            }
        });
        difAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                aplicarDifuminado(a);
            }
        });
        difAnim.start();
    }

    void difuminarYa() {
        if (difAnim != null) difAnim.cancel();
        aplicarDifuminado(0f);
    }

    private void aplicarDifuminado(float px) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            android.graphics.RenderEffect fx = px < 0.5f ? null
                    : android.graphics.RenderEffect.createBlurEffect(px, px,
                    android.graphics.Shader.TileMode.CLAMP);
            fila.setRenderEffect(fx);
            inferior.setRenderEffect(fx);
        } catch (Throwable t) {
            // sin desenfoque de contenido
        }
    }

    boolean isExpandido() {
        return expandido;
    }

    /** Cometa de luz del color del evento que da una vuelta al borde de la isla. */
    void destello(final int color) {
        if (!cfg.glow || cfg.style >= 3) return;
        if (luzAnim != null) luzAnim.cancel();
        luzAnim = ValueAnimator.ofFloat(0f, 1f);
        luzAnim.setDuration(cfg.ms(cfg.style == 1 ? 1100 : cfg.style == 2 ? 900 : 1500));
        luzAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        luzAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                // Gira una vuelta y media; aparece rapido y se apaga al final
                float alfa = f < 0.15f ? f / 0.15f : (f > 0.7f ? (1f - f) / 0.3f : 1f);
                if (cfg.style == 0) {
                    // Hyper: cometa de luz que da vueltas
                    fondo.setLuz(color, 540f * f, alfa);
                } else if (cfg.style == 1) {
                    // Cristal: solo un reflejo de vidrio que barre la superficie
                    fondo.setReflejo(f < 0.65f ? f / 0.65f : -1f);
                } else {
                    // Neon: el borde de luz se dibuja solo alrededor
                    fondo.setTrazo(f < 0.7f ? f / 0.7f : 1f);
                }
            }
        });
        luzAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                fondo.setLuz(0, 0f, 0f);
                fondo.setTrazo(-1f);
                fondo.setReflejo(-1f);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                fondo.setLuz(0, 0f, 0f);
                fondo.setTrazo(-1f);
                fondo.setReflejo(-1f);
            }
        });
        luzAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (luzAnim != null) luzAnim.cancel();
        if (auraAnim != null) {
            auraAnim.cancel();
            auraAnim = null;
        }
        if (contador != null) contador.cancel();
        crono.stop();
        super.onDetachedFromWindow();
    }

    /**
     * Muestra el evento en el estado pedido.
     * animar: lanza las animaciones de entrada (icono, contador, barra).
     */
    void configurar(final Evento e, boolean ex, boolean animar) {
        ev = e;
        expandido = ex;
        final boolean exOrig = ex;
        final boolean idle = e != null && e.tipo == Evento.IDLE;
        final boolean bn = ex && esBanner(cfg, e);
        if (bn) ex = false;
        if (!animar) {
            reposo();
            difuminarYa();
        }
        if (contador != null) {
            contador.cancel();
            contador = null;
        }
        crono.stop();
        final boolean dividido = !ex && !bn && cutoutPx > 0;

        // ---- espacios
        if (ex) {
            setPadding(dp(14), dp(10), dp(16), dp(10));
        } else if (bn) {
            setPadding(dp(14), 0, dp(16), 0);
        } else {
            setPadding(dp(dividido ? 10 : 9), 0, dp(10), 0);
        }

        if (e == null || idle) {
            actualizarAura(null, ex);
            fondo.setProgreso(0, -1f);
            iconoFrame.setVisibility(GONE);
            textos.setVisibility(GONE);
            hueco.setVisibility(GONE);
            derecha.setVisibility(GONE);
            inferior.setVisibility(GONE);
            return;
        }

        actualizarAura(e, exOrig);

        // ---- icono o glifo
        boolean usaGlifo = e.glifo != 0 || (e.icono == null && e.tipo == Evento.MUSICA);
        boolean conIcono = usaGlifo || cfg.showIcon;
        iconoFrame.setVisibility(conIcono ? VISIBLE : GONE);
        int lado = ex ? Math.max(dp(32), dp(cfg.heightDp - 28))
                : dp(bn ? 22 : dividido ? 20 : 22);
        LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) iconoFrame.getLayoutParams();
        ilp.width = lado;
        ilp.height = lado;
        iconoFrame.setLayoutParams(ilp);
        if (usaGlifo) {
            icono.setVisibility(GONE);
            glifo.setVisibility(VISIBLE);
            int tipoGlifo = e.glifo != 0 ? e.glifo : GlyphView.NOTA;
            glifo.set(tipoGlifo, e.nivel < 0 ? 0f : e.nivel / 100f, e.activo, e.sub, e.color);
            // El glifo se anima al expandir; si ya esta activo tambien en compacto (llamada, descarga)
            if (animar && exOrig) {
                glifo.entrada();
            } else if (e.activo && (e.tipo == Evento.LLAMADA || e.tipo == Evento.PROGRESO
                    || e.tipo == Evento.PRIVACIDAD)) {
                glifo.entrada();
            }
        } else {
            glifo.setVisibility(GONE);
            icono.setVisibility(VISIBLE);
            icono.setImageDrawable(e.icono);
        }

        // ---- textos o hueco para el notch
        textos.setVisibility(dividido ? GONE : VISIBLE);
        boolean huecoBn = bn && cutoutPx > 0;
        hueco.setVisibility(dividido || huecoBn ? VISIBLE : GONE);
        LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) hueco.getLayoutParams();
        if (huecoBn) {
            hlp.width = cutoutPx + dp(14);
            hlp.weight = 0f;
        } else {
            hlp.width = 0;
            hlp.weight = 1f;
        }
        hueco.setLayoutParams(hlp);
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) textos.getLayoutParams();
        tlp.setMarginStart(conIcono ? (ex ? dp(12) : dp(8)) : dp(4));
        textos.setLayoutParams(tlp);

        String tit = String.valueOf(e.titulo);
        String txt = String.valueOf(e.texto);
        boolean cambioTexto = ultTitulo != null && (!tit.equals(ultTitulo) || !txt.equals(ultTexto));
        ultTitulo = tit;
        ultTexto = txt;
        titulo.setText(e.titulo);
        titulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, bn ? 13 : ex ? 14 : 12);
        // Texto largo: en expandido se desplaza una vez para poder leerlo entero
        titulo.setEllipsize(ex ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        titulo.setSelected(ex);
        cuerpo.setText(e.texto);
        cuerpo.setEllipsize(ex ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        cuerpo.setSelected(ex);
        cuerpo.setVisibility(ex && !TextUtils.isEmpty(e.texto) ? VISIBLE : GONE);
        boolean conLeyenda = ex && e.tipo == Evento.NOTIF && !TextUtils.isEmpty(e.app);
        leyenda.setVisibility(conLeyenda ? VISIBLE : GONE);
        if (conLeyenda) {
            leyenda.setText(e.agrupadas > 1 ? e.app + " \u00B7 " + e.agrupadas + " mensajes"
                    : e.app);
            leyenda.setTextColor(e.color == 0xFFFFFFFF ? 0xFF9A9AA2 : e.color);
        }
        boolean conFoto = ex && e.imagen != null;
        miniatura.setVisibility(conFoto ? VISIBLE : GONE);
        if (conFoto) miniatura.setImageDrawable(e.imagen);
        if (cambioTexto && (ex || bn) && !animar && cfg.style >= 3) {
            // Xiaomi / iPhone: el contenido se desenfoca al cambiar
            textos.animate().cancel();
            textos.setAlpha(1f);
            textos.setTranslationX(0f);
            difuminar(10f, 0f, cfg.ms(240));
        } else if (cambioTexto && ex && !animar) {
            // Fundido corto cuando el texto cambia sin que cambie el evento
            textos.setAlpha(0.15f);
            textos.setTranslationX(dp(16));
            textos.animate().alpha(1f).translationX(0f).setDuration(cfg.ms(260)).start();
        } else {
            textos.animate().cancel();
            textos.setAlpha(1f);
            textos.setTranslationX(0f);
        }

        // ---- indicador de la derecha
        ecual.setAnimando(false);
        ecual.setVisibility(GONE);
        valor.setVisibility(GONE);
        crono.setVisibility(GONE);
        anillo.setVisibility(GONE);
        derecha.setVisibility(GONE);
        if (bn) {
            // Banner de estado: titulo a la izquierda, valor a la derecha (la camara en medio)
            derecha.setVisibility(VISIBLE);
            valor.setVisibility(VISIBLE);
            valor.setTextColor(e.color);
            valor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            valor.setMaxWidth(dp(150));
            valor.setSingleLine(true);
            valor.setEllipsize(TextUtils.TruncateAt.END);
            boolean porc = e.nivel >= 0 && (e.tipo == Evento.CARGA || e.tipo == Evento.BATERIA
                    || e.tipo == Evento.VOLUMEN);
            valor.setText(porc ? e.nivel + "%" : String.valueOf(e.texto));
        } else if (e.tipo == Evento.MUSICA) {
            derecha.setVisibility(VISIBLE);
            ecual.setVisibility(VISIBLE);
            ecual.setColor(e.color);
            ecual.setAnimando(e.reproduciendo);
            FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) ecual.getLayoutParams();
            elp.width = ex ? dp(24) : dp(18);
            elp.height = ex ? dp(20) : dp(14);
            ecual.setLayoutParams(elp);
        } else if ((e.tipo == Evento.LLAMADA && e.sub == 2 && e.base > 0)
                || (e.tipo == Evento.LINTERNA && e.activo && e.base > 0)
                || (e.tipo == Evento.CRONO && e.base > 0)) {
            derecha.setVisibility(VISIBLE);
            crono.setVisibility(VISIBLE);
            crono.setCountDown(e.cuentaAtras);
            crono.setTextColor(e.color);
            crono.setTextSize(TypedValue.COMPLEX_UNIT_SP, ex ? 16 : 11);
            crono.setBase(e.base);
            crono.start();
        } else if (e.tipo == Evento.PROGRESO && e.nivel >= 0) {
            derecha.setVisibility(VISIBLE);
            if (ex) {
                valor.setVisibility(VISIBLE);
                valor.setTextColor(e.color);
                valor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                valor.setText(e.nivel + "%");
            } else {
                anillo.setVisibility(VISIBLE);
                anillo.set(e.color, e.nivel / 100f);
            }
        } else if ((e.tipo == Evento.CARGA || e.tipo == Evento.BATERIA
                || e.tipo == Evento.VOLUMEN) && e.nivel >= 0) {
            derecha.setVisibility(VISIBLE);
            valor.setVisibility(VISIBLE);
            valor.setTextColor(e.color);
            valor.setTextSize(TypedValue.COMPLEX_UNIT_SP, ex ? 20 : 11);
            if (ex && animar && e.nivel > 0 && e.tipo != Evento.VOLUMEN) {
                final int destino = e.nivel;
                contador = ValueAnimator.ofInt(0, destino);
                contador.setDuration(cfg.ms(750));
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
        } else if (e.tipo == Evento.NOTIF && !ex && e.minimizada) {
            // Notificacion minimizada: un punto de color avisa de que hay algo que leer
            derecha.setVisibility(VISIBLE);
            valor.setVisibility(VISIBLE);
            valor.setTextColor(e.color);
            valor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            valor.setText(e.agrupadas > 1 ? String.valueOf(e.agrupadas) : "●");
        }

        boolean conBorde = cfg.progBorde && (e.tipo == Evento.CARGA || e.tipo == Evento.PROGRESO
                || e.tipo == Evento.VOLUMEN) && e.nivel >= 0;
        fondo.setProgreso(e.color, conBorde ? e.nivel / 100f : -1f);

        // ---- parte inferior
        boolean conBotones = ex && e.botonTxt != null && e.botonTxt.length > 0;
        boolean conBarra = ex && (e.tipo == Evento.MUSICA || e.tipo == Evento.CARGA
                || e.tipo == Evento.PROGRESO || e.tipo == Evento.VOLUMEN);
        if (!conBarra && !conBotones) {
            inferior.setVisibility(GONE);
            if (ex && animar) escalonar(false);
            return;
        }
        inferior.setVisibility(VISIBLE);
        if (ex && animar) escalonar(true);

        if (conBarra) {
            barraFrame.setVisibility(VISIBLE);
            boolean musica = e.tipo == Evento.MUSICA;
            LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) barraFrame.getLayoutParams();
            flp.height = musica ? dp(18) : dp(4);
            flp.topMargin = musica ? dp(6) : dp(10);
            barraFrame.setLayoutParams(flp);
            ((FrameLayout.LayoutParams) barra.getLayoutParams()).height = dp(4);
        } else {
            barraFrame.setVisibility(GONE);
        }

        if (e.tipo == Evento.MUSICA) {
            controles.setVisibility(VISIBLE);
            barra.setBrillo(false);
            barra.setColores(e.color, (e.color & 0x00FFFFFF) | 0x38000000);
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
                    e.color);
            bSiguiente.set(GlyphView.SIGUIENTE, 0f, false, 0, 0xFFFFFFFF);
        } else {
            controles.setVisibility(GONE);
            if (conBarra) {
                barra.setColores(e.color, 0x33FFFFFF);
                barra.setBrillo(true);
                float f = Math.max(0, e.nivel) / 100f;
                if (animar) {
                    barra.setProgreso(0f);
                    barra.animarA(f, cfg.ms(750));
                } else {
                    barra.setProgreso(f);
                }
            }
        }

        if (conBotones) {
            if (respondiendoVista) {
                botones.setVisibility(VISIBLE);
            } else {
                construirBotones(e);
            }
        } else {
            botones.setVisibility(GONE);
            botonesDe = null;
        }
    }

    /**
     * Luz interior del evento. Los eventos activos (musica sonando, carga, linterna, llamada)
     * la hacen respirar; los demas la muestran fija mientras estan expandidos.
     */
    private void actualizarAura(Evento e, boolean ex) {
        boolean fija = e != null && cfg.aura
                && ((ex && e.tipo != Evento.MUSICA) || (ex && e.reproduciendo));
        boolean respira = e != null && cfg.aura
                && (e.activo || (e.tipo == Evento.MUSICA && e.reproduciendo))
                && e.tipo != Evento.NOTIF;
        if (e == null || !cfg.aura || (!fija && !respira)) {
            if (auraAnim != null) {
                auraAnim.cancel();
                auraAnim = null;
            }
            fondo.setAura(0, 0f);
            return;
        }
        auraColor = e.color;
        if (!respira) {
            if (auraAnim != null) {
                auraAnim.cancel();
                auraAnim = null;
            }
            fondo.setAura(auraColor, 0.55f);
            return;
        }
        fondo.setAura(auraColor, 0.8f);
        if (auraAnim != null || !isAttachedToWindow()) return;
        auraAnim = ValueAnimator.ofFloat(0f, 1f);
        auraAnim.setDuration(2600);
        auraAnim.setRepeatMode(ValueAnimator.REVERSE);
        auraAnim.setRepeatCount(ValueAnimator.INFINITE);
        auraAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        auraAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                fondo.setAura(auraColor, 0.45f + 0.55f * (Float) a.getAnimatedValue());
            }
        });
        auraAnim.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ev != null) actualizarAura(ev, expandido);
    }

    /** Todo en su sitio, sin restos de una animacion cortada a medias. */
    private void reposo() {
        View[] vs = {iconoFrame, titulo, cuerpo, derecha, inferior};
        for (View v : vs) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationY(0f);
        }
    }

    /** Entrada en cascada: el icono salta, los textos suben con fundido, luego lo demas. */
    private void escalonar(boolean conInferior) {
        iconoFrame.setPivotX(iconoFrame.getWidth() / 2f);
        iconoFrame.setScaleX(0.4f);
        iconoFrame.setScaleY(0.4f);
        iconoFrame.setAlpha(0f);
        iconoFrame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(cfg.ms(420))
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        View[] tx = {titulo, cuerpo};
        for (int i = 0; i < tx.length; i++) {
            tx[i].setAlpha(0f);
            tx[i].setTranslationY(dp(8));
            tx[i].animate().alpha(1f).translationY(0f).setStartDelay(cfg.ms(60 + 55 * i))
                    .setDuration(cfg.ms(300))
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
        derecha.setAlpha(0f);
        derecha.setScaleX(0.6f);
        derecha.setScaleY(0.6f);
        derecha.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(cfg.ms(150))
                .setDuration(cfg.ms(340))
                .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
        if (conInferior) {
            inferior.setAlpha(0f);
            inferior.setTranslationY(dp(6));
            inferior.animate().alpha(1f).translationY(0f).setStartDelay(cfg.ms(200))
                    .setDuration(cfg.ms(300))
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    private void construirBotones(Evento e) {
        botones.setVisibility(VISIBLE);
        if (botonesDe == e.botonTxt) return;
        botonesDe = e.botonTxt;
        botones.removeAllViews();
        for (int i = 0; i < e.botonTxt.length; i++) {
            final int idx = i;
            TextView t = new TextView(getContext());
            t.setText(e.botonTxt[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(8), 0, dp(8), 0);
            GradientDrawable fondo = new GradientDrawable();
            int col = e.botonColor != null && i < e.botonColor.length ? e.botonColor[i]
                    : 0x33FFFFFF;
            if (col == 0x33FFFFFF && cfg.style == 0) col = 0xFF2A2A2F;
            fondo.setColor(col);
            fondo.setCornerRadius(dp(18));
            if (cfg.style == 1) fondo.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
            t.setBackground(fondo);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LayoutParams.MATCH_PARENT, 1f);
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            botones.addView(t, lp);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Evento actual = ev;
                    if (actual != null && actual.botonRun != null && idx < actual.botonRun.length
                            && actual.botonRun[idx] != null) {
                        try {
                            actual.botonRun[idx].run();
                        } catch (Throwable ignorado) {
                            // un boton que falla no debe tumbar SystemUI
                        }
                    }
                    if (alBoton != null) alBoton.run();
                }
            });
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
