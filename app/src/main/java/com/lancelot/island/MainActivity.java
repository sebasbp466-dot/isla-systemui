package com.lancelot.island;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
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

/** Pantalla de ajustes de la isla: vista previa en vivo, personalizacion y notificacion de prueba. */
public class MainActivity extends Activity {

    private interface IntSetter {
        void set(int v);
    }

    private interface Etiqueta {
        String de(int v);
    }

    private IslaConfig cfg;
    private FrameLayout vista;
    private TextView estado;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        cfg = IslaConfig.load(this);
        construirUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        actualizarEstado();
    }

    // ------------------------------------------------------------------ interfaz

    private void construirUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(32));
        scroll.addView(col);
        setContentView(scroll);

        texto(col, "Isla SystemUI", 24, true);

        estado = texto(col, "", 13, false);
        boton(col, "Conceder permiso (root)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                concederPermiso();
            }
        });

        texto(col, "Vista previa", 16, true);
        vista = new FrameLayout(this);
        vista.setBackgroundColor(0xFF3A3A3A);
        col.addView(vista, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
        refrescar();

        interruptor(col, "Isla activada", cfg.enabled, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.enabled = v == 1;
            }
        });

        texto(col, "Tamaño y posición", 16, true);
        seek(col, "Ancho", 200, 360, cfg.widthDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.widthDp = v;
            }
        });
        seek(col, "Alto", 48, 100, cfg.heightDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.heightDp = v;
            }
        });
        seek(col, "Margen superior", 0, 60, cfg.topDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.topDp = v;
            }
        });
        seek(col, "Redondeo", 0, 50, cfg.radiusDp, dpTxt(), new IntSetter() {
            @Override
            public void set(int v) {
                cfg.radiusDp = v;
            }
        });
        seek(col, "Duración", 15, 120, cfg.durationMs / 100, new Etiqueta() {
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

        texto(col, "Colores", 16, true);
        hex(col, "Fondo", cfg.bgColor, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.bgColor = v;
            }
        });
        hex(col, "Título", cfg.titleColor, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.titleColor = v;
            }
        });
        hex(col, "Texto", cfg.bodyColor, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.bodyColor = v;
            }
        });

        texto(col, "Comportamiento", 16, true);
        interruptor(col, "Animación con rebote", cfg.bounce, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.bounce = v == 1;
            }
        });
        interruptor(col, "Mostrar icono", cfg.showIcon, new IntSetter() {
            @Override
            public void set(int v) {
                cfg.showIcon = v == 1;
            }
        });

        texto(col, "Apps ignoradas (paquetes separados por coma)", 14, false);
        EditText bloqueadas = new EditText(this);
        bloqueadas.setHint("com.whatsapp,com.instagram.android");
        bloqueadas.setText(cfg.blocked);
        bloqueadas.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable ed) {
                cfg.blocked = IslaConfig.limpiar(ed.toString());
            }
        });
        col.addView(bloqueadas);

        boton(col, "Guardar y aplicar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guardar(null);
            }
        });
        boton(col, "Enviar notificación de prueba", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probar();
            }
        });
        boton(col, "Restablecer valores", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg = new IslaConfig();
                construirUi();
                actualizarEstado();
            }
        });
    }

    private void refrescar() {
        if (vista == null) return;
        vista.removeAllViews();
        IslandView p = new IslandView(this, cfg);
        p.bind("Isla SystemUI", "Así se verá tu notificación",
                getDrawable(android.R.drawable.sym_def_app_icon));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(cfg.widthDp), dp(cfg.heightDp), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(cfg.topDp);
        vista.addView(p, lp);
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

    // ------------------------------------------------------------------ ayudas de interfaz

    private TextView texto(LinearLayout col, String s, int sp, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (negrita) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(negrita ? 16 : 8), 0, dp(4));
        col.addView(t);
        return t;
    }

    private void boton(LinearLayout col, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        col.addView(b);
    }

    private void interruptor(LinearLayout col, String nombre, boolean valor, final IntSetter s) {
        Switch sw = new Switch(this);
        sw.setText(nombre);
        sw.setChecked(valor);
        sw.setPadding(0, dp(8), 0, dp(8));
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
        final TextView t = texto(col, nombre + ": " + et.de(valor), 14, false);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(valor - min);
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

    private void hex(LinearLayout col, String nombre, int color, final IntSetter s) {
        texto(col, nombre + " (RRGGBB o AARRGGBB)", 14, false);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(String.format(Locale.ROOT, "%08X", color));
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
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
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
