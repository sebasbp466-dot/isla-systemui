package com.lancelot.island;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ajustes de la isla con dos estilos de interfaz:
 * - Material You: colores dinamicos del sistema, superficies tonales, botones tipo pildora.
 * - Liquid Glass: fondo con degradados de color y superficies de cristal translucido con brillo.
 */
public class MainActivity extends Activity {

    private interface IntSetter {
        void set(int v);
    }

    private interface Etiqueta {
        String de(int v);
    }

    private static final int REL = 0;   // boton relleno
    private static final int TON = 1;   // boton tonal
    private static final int CON = 2;   // boton de contorno

    private IslaConfig cfg;
    private boolean vidrio;
    private boolean noche;
    private float d;

    // Colores del tema actual
    private int cText;
    private int cSub;
    private int cPrimary;
    private int cOnPrimary;
    private int cOutline;
    private int cPage;
    private int cCard;

    private ScrollView scroll;
    private FrameLayout vista;
    private TextView estado;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        d = getResources().getDisplayMetrics().density;
        cfg = IslaConfig.load(this);
        vidrio = prefs().getBoolean("vidrio", false);
        construirUi(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        actualizarEstado();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("app", MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ tema

    private int sys(int res, int fallback) {
        try {
            if (Build.VERSION.SDK_INT >= 31) return getColor(res);
        } catch (Throwable ignorado) {
            // se usa el color de reserva
        }
        return fallback;
    }

    private void aplicarTema() {
        noche = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int barraArriba;
        int barraAbajo;
        if (vidrio) {
            cText = 0xFFFFFFFF;
            cSub = 0xCCFFFFFF;
            cPrimary = 0xFFFFFFFF;
            cOnPrimary = 0xFF1B1B3A;
            cOutline = 0x66FFFFFF;
            cPage = 0xFF1B2A6B;
            cCard = 0x2EFFFFFF;
            barraArriba = 0xFF1B2A6B;
            barraAbajo = 0xFF0E7C86;
            getWindow().setBackgroundDrawable(paginaVidrio());
        } else {
            if (noche) {
                cText = sys(android.R.color.system_neutral1_100, 0xFFE6E1E5);
                cSub = sys(android.R.color.system_neutral2_200, 0xFFCAC4D0);
                cPrimary = sys(android.R.color.system_accent1_200, 0xFFD0BCFF);
                cOnPrimary = sys(android.R.color.system_accent1_800, 0xFF381E72);
                cOutline = sys(android.R.color.system_neutral2_500, 0xFF938F99);
                cPage = sys(android.R.color.system_neutral1_900, 0xFF1C1B1F);
                cCard = sys(android.R.color.system_neutral2_800, 0xFF2B2930);
            } else {
                cText = sys(android.R.color.system_neutral1_900, 0xFF1C1B1F);
                cSub = sys(android.R.color.system_neutral2_700, 0xFF49454F);
                cPrimary = sys(android.R.color.system_accent1_600, 0xFF6750A4);
                cOnPrimary = sys(android.R.color.system_neutral1_0, 0xFFFFFFFF);
                cOutline = sys(android.R.color.system_neutral2_500, 0xFF79747E);
                cPage = sys(android.R.color.system_neutral1_10, 0xFFFFFBFE);
                cCard = sys(android.R.color.system_neutral2_100, 0xFFF3EDF7);
            }
            barraArriba = cPage;
            barraAbajo = cPage;
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(cPage));
        }
        getWindow().setStatusBarColor(barraArriba);
        getWindow().setNavigationBarColor(barraAbajo);
        try {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                int m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                wic.setSystemBarsAppearance((!vidrio && !noche) ? m : 0, m);
            }
        } catch (Throwable ignorado) {
            // los iconos de la barra se quedan como estan
        }
    }

    private Drawable paginaVidrio() {
        GradientDrawable lin = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2A6B, 0xFF5B2A86, 0xFF0E7C86});
        Drawable b1 = brillo(0.15f, 0.12f, 380, 0x66FFFFFF, 0x00FFFFFF);
        Drawable b2 = brillo(0.90f, 0.62f, 320, 0x5500E5FF, 0x0000E5FF);
        Drawable b3 = brillo(0.20f, 0.95f, 300, 0x55FF4DA6, 0x00FF4DA6);
        return new LayerDrawable(new Drawable[]{lin, b1, b2, b3});
    }

    private Drawable brillo(float cx, float cy, int radioDp, int c0, int c1) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(dp(radioDp));
        g.setColors(new int[]{c0, c1});
        return g;
    }

    /** Superficie de una tarjeta segun el tema. */
    private Drawable fondoTarjeta() {
        if (vidrio) return cristal(0x2EFFFFFF, 0x66FFFFFF, 0x38FFFFFF, dp(28));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cCard);
        g.setCornerRadius(dp(24));
        return g;
    }

    /** Cristal: relleno translucido con borde claro y un brillo de arriba hacia abajo. */
    private Drawable cristal(int relleno, int borde, int brilloArriba, float radioPx) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(relleno);
        base.setCornerRadius(radioPx);
        base.setStroke(Math.max(1, dp(1)), borde);
        GradientDrawable shine = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{brilloArriba, 0x00FFFFFF});
        shine.setCornerRadius(radioPx);
        return new LayerDrawable(new Drawable[]{base, shine});
    }

    // ------------------------------------------------------------------ interfaz

    private void reconstruir() {
        construirUi(scroll != null ? scroll.getScrollY() : 0);
    }

    private void construirUi(final int scrollY) {
        aplicarTema();

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(col);
        setContentView(scroll);

        TextView titulo = texto(col, "Isla SystemUI", 30, true, cText);
        titulo.setPadding(dp(4), dp(8), 0, 0);
        TextView sub = texto(col, "Dynamic island para SystemUI", 14, false, cSub);
        sub.setPadding(dp(4), 0, 0, dp(12));

        // ---- Apariencia de la app
        LinearLayout t = tarjeta(col, "Apariencia de la app");
        segmentado(t, new String[]{"Material You", "Liquid Glass"}, vidrio ? 1 : 0,
                new IntSetter() {
                    @Override
                    public void set(int v) {
                        vidrio = v == 1;
                        prefs().edit().putBoolean("vidrio", vidrio).apply();
                        reconstruir();
                    }
                });

        // ---- Estado
        LinearLayout e = tarjeta(col, "Estado");
        estado = texto(e, "", 13, false, cSub);
        boton(e, "Conceder permiso (root)", TON, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                concederPermiso();
            }
        });

        // ---- Vista previa
        LinearLayout p = tarjeta(col, "Vista previa");
        vista = new FrameLayout(this);
        GradientDrawable pared = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2B5876, 0xFF4E4376});
        pared.setCornerRadius(dp(20));
        vista.setBackground(pared);
        vista.setClipToOutline(true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190));
        vlp.topMargin = dp(8);
        p.addView(vista, vlp);
        refrescar();
        texto(p, "Arriba: expandida. Abajo: compacta con hueco para la cámara.", 12, false, cSub);

        // ---- Estilo de la isla
        LinearLayout s = tarjeta(col, "Estilo de la isla");
        segmentado(s, new String[]{"Hyper (sólida)", "Cristal"}, cfg.style, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.style = v;
                reconstruir();
            }
        });
        interruptor(s, "Isla activada", cfg.enabled, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.enabled = v == 1;
            }
        });
        interruptor(s, "Animación con rebote", cfg.bounce, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.bounce = v == 1;
            }
        });
        interruptor(s, "Mostrar icono", cfg.showIcon, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.showIcon = v == 1;
                refrescar();
            }
        });

        // ---- Funciones
        LinearLayout fu = tarjeta(col, "Qué muestra la isla");
        interruptor(fu, "Notificaciones", cfg.fNotif, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.fNotif = v == 1;
            }
        });
        interruptor(fu, "Música (cápsula con ecualizador)", cfg.fMusica, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.fMusica = v == 1;
            }
        });
        interruptor(fu, "Carga", cfg.fCarga, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.fCarga = v == 1;
            }
        });
        interruptor(fu, "Batería baja y carga completa", cfg.fBateria, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.fBateria = v == 1;
            }
        });
        interruptor(fu, "Linterna", cfg.fLinterna, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.fLinterna = v == 1;
            }
        });
        interruptor(fu, "Modo de sonido", cfg.fTimbre, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.fTimbre = v == 1;
            }
        });
        interruptor(fu, "Auriculares y Bluetooth", cfg.fAuric, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.fAuric = v == 1;
            }
        });

        // ---- Posición
        LinearLayout po = tarjeta(col, "Posición al expandirse");
        texto(po, "La cápsula compacta siempre queda en el notch. Al expandirse puede bajar "
                + "debajo de la barra de estado (no tapa la hora ni los iconos) o ir encima.",
                12, false, cSub);
        segmentado(po, new String[]{"Bajo la barra", "Sobre la barra"}, cfg.expandedPos,
                new IntSetter() {
                    @Override
                    public void set(int v) {
                        cfg.expandedPos = v;
                        reconstruir();
                    }
                });

        // ---- Tamaño y posición
        LinearLayout z = tarjeta(col, "Tamaño y posición");
        seek(z, "Ancho expandida", 200, 360, cfg.widthDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.widthDp = v;
            }
        });
        seek(z, "Alto expandida", 48, 100, cfg.heightDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.heightDp = v;
            }
        });
        seek(z, "Ancho mínimo compacta", 90, 200, cfg.compactW, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.compactW = v;
            }
        });
        seek(z, "Ajuste vertical al expandir", 0, 60, cfg.topDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.topDp = v;
            }
        });
        seek(z, "Redondeo", 0, 50, cfg.radiusDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.radiusDp = v;
            }
        });
        seek(z, "Tiempo expandida", 15, 120, cfg.durationMs / 100, new Etiqueta() {
            @Override
            public String de(int v) {
                return String.format(Locale.ROOT, "%.1f s", v / 10f);
            }
        }, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.durationMs = v * 100;
            }
        });

        // ---- Colores
        LinearLayout c = tarjeta(col, "Colores (RRGGBB o AARRGGBB)");
        hex(c, "Fondo", cfg.bgColor, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.bgColor = v;
            }
        });
        hex(c, "Título", cfg.titleColor, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.titleColor = v;
            }
        });
        hex(c, "Texto", cfg.bodyColor, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.bodyColor = v;
            }
        });

        // ---- Apps ignoradas
        LinearLayout a = tarjeta(col, "Apps ignoradas");
        texto(a, "Paquetes separados por coma", 12, false, cSub);
        EditText bloqueadas = campo("com.whatsapp,com.instagram.android");
        bloqueadas.setText(cfg.blocked);
        bloqueadas.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int i, int j, int k) {
            }

            @Override
            public void onTextChanged(CharSequence x, int i, int j, int k) {
            }

            @Override
            public void afterTextChanged(Editable ed) {
                cfg.blocked = IslaConfig.limpiar(ed.toString());
            }
        });
        a.addView(bloqueadas);

        // ---- Acciones
        LinearLayout ac = tarjeta(col, "Acciones");
        boton(ac, "Guardar y aplicar", REL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guardar(null);
            }
        });
        boton(ac, "Enviar notificación de prueba", TON, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probar();
            }
        });

        // ---- Pruebas de eventos (se envían a SystemUI, no hace falta cargar ni encender nada)
        LinearLayout pr = tarjeta(col, "Probar eventos");
        texto(pr, "Guarda los ajustes y manda el evento a la isla.", 12, false, cSub);
        filaPrueba(pr, "Carga", 1, "Linterna", 2);
        filaPrueba(pr, "Linterna apagada", 3, "Música", 4);
        filaPrueba(pr, "Modo silencio", 5, "Auriculares", 6);
        filaPrueba(pr, "Batería baja", 7, "Batería llena", 8);
        boton(pr, "Quitar la música de prueba", CON, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probarEvento(9);
            }
        });
        boton(ac, "Restablecer valores", CON, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg = new IslaConfig();
                reconstruir();
            }
        });

        actualizarEstado();
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, scrollY);
            }
        });
    }

    private void refrescar() {
        if (vista == null) return;
        vista.removeAllViews();

        Evento muestra = new Evento(Evento.NOTIF);
        muestra.titulo = "Isla SystemUI";
        muestra.texto = "Así se verá tu notificación";
        muestra.icono = getDrawable(android.R.drawable.sym_def_app_icon);

        int h1 = dp(IslandView.alturaDp(cfg, muestra));
        IslandView grande = new IslandView(this, cfg, 0);
        grande.configurar(muestra, true, false);
        grande.setCorner(Math.min(dp(cfg.radiusDp), h1 / 2f));
        FrameLayout.LayoutParams l1 = new FrameLayout.LayoutParams(
                dp(cfg.widthDp), h1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        l1.topMargin = dp(8);
        vista.addView(grande, l1);

        // Compacta de ejemplo con un notch de 28 dp: el hueco queda en medio
        int notch = dp(28);
        int h2 = dp(30);
        IslandView chica = new IslandView(this, cfg, notch);
        chica.configurar(muestra, false, false);
        chica.setCorner(h2 / 2f);
        FrameLayout.LayoutParams l2 = new FrameLayout.LayoutParams(
                Math.max(dp(cfg.compactW), notch + dp(68)), h2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        l2.topMargin = dp(8) + h1 + dp(14);
        vista.addView(chica, l2);
    }

    private void actualizarEstado() {
        if (estado == null) return;
        boolean ok = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        estado.setText(ok
                ? "Permiso para guardar ajustes: OK"
                : "Falta el permiso para guardar ajustes. Pulsa el botón (pide root) o ejecuta en Termux:\n"
                + "su -c \"pm grant " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS\"");
    }

    // ------------------------------------------------------------------ acciones

    private void concederPermiso() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pm grant " + getPackageName()
                                    + " android.permission.WRITE_SECURE_SETTINGS"});
                    p.waitFor();
                } catch (Throwable ignorado) {
                    // se avisa abajo si no quedo concedido
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        actualizarEstado();
                        Toast.makeText(MainActivity.this, "Listo, revisa el estado",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void guardar(final Runnable despues) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = IslaConfig.save(getApplicationContext(), cfg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                ok ? "Guardado" : "No se pudo guardar: falta el permiso",
                                Toast.LENGTH_SHORT).show();
                        actualizarEstado();
                        if (ok && despues != null) despues.run();
                    }
                });
            }
        }).start();
    }

    private void probar() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            Toast.makeText(this, "Acepta el permiso de notificaciones y vuelve a pulsar Probar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        guardar(new Runnable() {
            @Override
            public void run() {
                enviarPrueba();
            }
        });
    }

    /** Dos botones de prueba en una fila. */
    private void filaPrueba(LinearLayout col, String a, final int tipoA, String b,
                            final int tipoB) {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(fila, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        boton(fila, a, TON, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probarEvento(tipoA);
            }
        });
        boton(fila, b, TON, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probarEvento(tipoB);
            }
        });
        for (int i = 0; i < fila.getChildCount(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    fila.getChildAt(i).getLayoutParams();
            lp.width = 0;
            lp.weight = 1f;
            if (i > 0) lp.setMarginStart(dp(8));
            fila.getChildAt(i).setLayoutParams(lp);
        }
    }

    private void probarEvento(final int tipo) {
        guardar(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(Eventos.ACCION_PRUEBA);
                i.setPackage("com.android.systemui");
                i.putExtra("tipo", tipo);
                sendBroadcast(i);
            }
        });
    }

    private void enviarPrueba() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "prueba", "Pruebas de la isla", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String hora = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        Notification n = new Notification.Builder(this, "prueba")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Isla SystemUI")
                .setContentText("Notificación de prueba " + hora)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }

    // ------------------------------------------------------------------ componentes

    private TextView texto(LinearLayout col, String s, int sp, boolean negrita, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (negrita) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        col.addView(t);
        return t;
    }

    private LinearLayout tarjeta(LinearLayout col, String titulo) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(16));
        c.setBackground(fondoTarjeta());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        col.addView(c, lp);
        TextView t = texto(c, titulo, 15, true, vidrio ? 0xFFFFFFFF : cPrimary);
        t.setPadding(0, 0, 0, dp(6));
        return c;
    }

    private Drawable fondoBoton(int tipo) {
        float r = dp(28);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(r);
        int ripple;
        if (vidrio) {
            ripple = 0x33FFFFFF;
            if (tipo == REL) {
                return new RippleDrawable(ColorStateList.valueOf(ripple),
                        cristal(0x66FFFFFF, 0xAAFFFFFF, 0x55FFFFFF, r), null);
            } else if (tipo == TON) {
                return new RippleDrawable(ColorStateList.valueOf(ripple),
                        cristal(0x26FFFFFF, 0x66FFFFFF, 0x30FFFFFF, r), null);
            }
            g.setColor(Color.TRANSPARENT);
            g.setStroke(Math.max(1, dp(1)), 0x88FFFFFF);
        } else {
            ripple = (cPrimary & 0x00FFFFFF) | 0x33000000;
            if (tipo == REL) {
                g.setColor(cPrimary);
            } else if (tipo == TON) {
                g.setColor((cPrimary & 0x00FFFFFF) | 0x2E000000);
            } else {
                g.setColor(Color.TRANSPARENT);
                g.setStroke(Math.max(1, dp(1)), cOutline);
            }
        }
        return new RippleDrawable(ColorStateList.valueOf(ripple), g, null);
    }

    private void boton(LinearLayout col, String s, int tipo, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setBackground(fondoBoton(tipo));
        int texto;
        if (vidrio) {
            texto = 0xFFFFFFFF;
        } else {
            texto = tipo == REL ? cOnPrimary : cPrimary;
        }
        b.setTextColor(texto);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        col.addView(b, lp);
    }

    private void segmentado(LinearLayout col, String[] opciones, int sel, final IntSetter s) {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(6);
        col.addView(fila, flp);
        for (int i = 0; i < opciones.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(opciones[i]);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setStateListAnimator(null);
            b.setMinHeight(dp(44));
            b.setBackground(fondoBoton(i == sel ? REL : CON));
            int texto;
            if (vidrio) {
                texto = 0xFFFFFFFF;
            } else {
                texto = i == sel ? cOnPrimary : cPrimary;
            }
            b.setTextColor(texto);
            if (i == sel) b.setTypeface(b.getTypeface(), Typeface.BOLD);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s.set(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(8));
            fila.addView(b, lp);
        }
    }

    private void interruptor(LinearLayout col, String nombre, boolean valor, final IntSetter s) {
        Switch sw = new Switch(this);
        sw.setText(nombre);
        sw.setTextColor(cText);
        sw.setTextSize(15);
        sw.setChecked(valor);
        sw.setPadding(0, dp(10), 0, dp(10));
        int[][] est = {{android.R.attr.state_checked}, {}};
        if (vidrio) {
            sw.setThumbTintList(new ColorStateList(est, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
            sw.setTrackTintList(new ColorStateList(est, new int[]{0xFF34C759, 0x66FFFFFF}));
        } else {
            sw.setThumbTintList(new ColorStateList(est, new int[]{cOnPrimary, cOutline}));
            sw.setTrackTintList(new ColorStateList(est,
                    new int[]{cPrimary, (cOutline & 0x00FFFFFF) | 0x40000000}));
        }
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean marcado) {
                s.set(marcado ? 1 : 0);
                refrescar();
            }
        });
        col.addView(sw);
    }

    private void seek(LinearLayout col, final String nombre, final int min, int max, int valor,
                      final Etiqueta et, final IntSetter s) {
        final TextView t = texto(col, nombre + ": " + et.de(valor), 14, false, cText);
        t.setPadding(0, dp(8), 0, 0);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(valor - min);
        int principal = vidrio ? 0xFFFFFFFF : cPrimary;
        int fondo = vidrio ? 0x55FFFFFF : ((cOutline & 0x00FFFFFF) | 0x55000000);
        sb.setProgressTintList(ColorStateList.valueOf(principal));
        sb.setThumbTintList(ColorStateList.valueOf(principal));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(fondo));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progreso, boolean usuario) {
                int v = progreso + min;
                s.set(v);
                t.setText(nombre + ": " + et.de(v));
                refrescar();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        col.addView(sb);
    }

    private EditText campo(String pista) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(pista);
        e.setTextColor(cText);
        e.setHintTextColor(cSub);
        e.setTextSize(14);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), vidrio ? 0x88FFFFFF : cOutline);
        g.setColor(vidrio ? 0x1AFFFFFF : Color.TRANSPARENT);
        e.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        e.setLayoutParams(lp);
        return e;
    }

    private void hex(LinearLayout col, String nombre, int color, final IntSetter s) {
        TextView t = texto(col, nombre, 13, false, cSub);
        t.setPadding(0, dp(8), 0, 0);
        EditText e = campo("AARRGGBB");
        e.setText(String.format(Locale.ROOT, "%08X", color));
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int i, int j, int k) {
            }

            @Override
            public void onTextChanged(CharSequence x, int i, int j, int k) {
            }

            @Override
            public void afterTextChanged(Editable ed) {
                String v = ed.toString().trim().replace("#", "");
                if (v.length() == 6) v = "FF" + v;
                if (v.length() != 8) return;
                try {
                    s.set((int) Long.parseLong(v, 16));
                    refrescar();
                } catch (NumberFormatException ignorado) {
                    // todavia no es un color valido
                }
            }
        });
        col.addView(e);
    }

    private Etiqueta dpTxt() {
        return new Etiqueta() {
            @Override
            public String de(int v) {
                return v + " dp";
            }
        };
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
