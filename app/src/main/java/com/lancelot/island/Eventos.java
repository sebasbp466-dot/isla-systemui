package com.lancelot.island;

import android.app.Notification;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Fuentes de eventos de la isla. Todo es por receptores y callbacks (nada de sondeo):
 * si no pasa nada, no se ejecuta nada.
 */
final class Eventos {

    static final String ACCION_PRUEBA = "com.lancelot.island.TEST";
    private static final String PERMISO_PRUEBA = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String TAG = "[IslaSystemUI] ";

    private static final int VERDE = 0xFF34C759;
    private static final int ROJO = 0xFFFF453A;
    private static final int NARANJA = 0xFFFF9F0A;
    private static final int AZUL = 0xFF0A84FF;
    private static final int ROSA = 0xFFFF375F;

    private static boolean iniciado;
    private static Context ctx;
    private static Handler h;

    private static boolean cargando;
    private static boolean lleno;
    private static int ultimoNivel = -1;
    private static int ultimoModo = -1;
    private static Boolean ultimaLinterna;
    private static long ignoraLinternaHasta;
    private static String ultimaClave = "";
    private static long ultimaClaveHora;
    private static BroadcastReceiver receptorBateria;

    private static final HashMap<String, Rastreo> RASTREOS = new HashMap<>();

    private Eventos() {
    }

    static synchronized void iniciar(Context c) {
        if (iniciado || c == null) return;
        iniciado = true;
        Context app = c.getApplicationContext();
        ctx = app != null ? app : c;
        h = new Handler(Looper.getMainLooper());
        IslaConfig.get(ctx);
        try {
            registrar();
            XposedBridge.log(TAG + "eventos iniciados");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static IslandController isla() {
        return IslandController.get(ctx);
    }

    // ------------------------------------------------------------------ registro

    private static void registrar() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        f.addAction(AudioManager.ACTION_HEADSET_PLUG);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        ctx.registerReceiver(receptor, f, null, h, Context.RECEIVER_NOT_EXPORTED);

        // Para probar desde la app o desde Termux: solo lo puede enviar quien tenga el permiso
        try {
            ctx.registerReceiver(prueba, new IntentFilter(ACCION_PRUEBA), PERMISO_PRUEBA, h,
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            // Al registrar, el sistema avisa del estado actual: se ignora un instante
            ignoraLinternaHasta = SystemClock.uptimeMillis() + 2500;
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    linterna(enabled);
                }
            }, h);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                String a = i.getAction();
                if (a == null) return;
                switch (a) {
                    case Intent.ACTION_POWER_CONNECTED:
                        conectado();
                        break;
                    case Intent.ACTION_POWER_DISCONNECTED:
                        desconectado();
                        break;
                    case Intent.ACTION_BATTERY_LOW:
                        bateriaBaja();
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        isla().pantalla(true);
                        break;
                    case Intent.ACTION_SCREEN_OFF:
                        isla().pantalla(false);
                        break;
                    case AudioManager.RINGER_MODE_CHANGED_ACTION:
                        timbre(i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1));
                        break;
                    case AudioManager.ACTION_HEADSET_PLUG:
                        cableAuriculares(i.getIntExtra("state", 0) == 1);
                        break;
                    case BluetoothDevice.ACTION_ACL_CONNECTED:
                        bluetooth(i, true);
                        break;
                    case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                        bluetooth(i, false);
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };

    // ------------------------------------------------------------------ carga y bateria

    private static Intent bateriaActual() {
        return ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private static int porcentaje(Intent bat) {
        if (bat == null) return -1;
        int nivel = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int escala = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (nivel < 0 || escala <= 0) return -1;
        return Math.round(nivel * 100f / escala);
    }

    private static Evento evCarga(Intent bat) {
        int plug = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
        Evento e = new Evento(Evento.CARGA);
        e.glifo = GlyphView.BATERIA;
        e.activo = true;
        e.nivel = porcentaje(bat);
        e.color = VERDE;
        e.titulo = "Cargando";
        if ((plug & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) {
            e.texto = "Carga inalámbrica";
        } else if ((plug & BatteryManager.BATTERY_PLUGGED_AC) != 0) {
            e.texto = "Cargador de pared";
        } else if ((plug & BatteryManager.BATTERY_PLUGGED_USB) != 0) {
            e.texto = "Conectado por USB";
        } else {
            e.texto = "Conectado";
        }
        return e;
    }

    private static void conectado() {
        cargando = true;
        lleno = false;
        Intent bat = bateriaActual();
        ultimoNivel = porcentaje(bat);
        isla().show(evCarga(bat));
        escucharBateria(true);
    }

    private static void desconectado() {
        cargando = false;
        escucharBateria(false);
        int n = porcentaje(bateriaActual());
        Evento e = new Evento(Evento.CARGA);
        e.glifo = GlyphView.BATERIA;
        e.activo = false;
        e.nivel = n;
        e.color = n <= 15 ? ROJO : 0xFFFFFFFF;
        e.titulo = "Desconectado";
        e.texto = "Batería al " + n + "%";
        isla().show(e);
    }

    private static void bateriaBaja() {
        int n = porcentaje(bateriaActual());
        Evento e = new Evento(Evento.BATERIA);
        e.glifo = GlyphView.BATERIA;
        e.activo = false;
        e.nivel = n;
        e.color = ROJO;
        e.titulo = "Batería baja";
        e.texto = n + "% restante";
        isla().show(e);
    }

    /** Solo mientras carga: avisa de cada cambio de porcentaje y detecta la carga completa. */
    private static void escucharBateria(boolean on) {
        if (on) {
            if (receptorBateria != null) return;
            receptorBateria = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        int n = porcentaje(i);
                        int estado = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        if (!lleno && cargando
                                && (estado == BatteryManager.BATTERY_STATUS_FULL || n >= 100)) {
                            lleno = true;
                            Evento e = new Evento(Evento.BATERIA);
                            e.glifo = GlyphView.CHECK;
                            e.nivel = 100;
                            e.color = VERDE;
                            e.titulo = "Batería cargada";
                            e.texto = "100%";
                            isla().show(e);
                        } else if (n != ultimoNivel && n >= 0) {
                            ultimoNivel = n;
                            isla().actualizarMismoTipo(evCarga(i));
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };
            try {
                ctx.registerReceiver(receptorBateria,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, h,
                        Context.RECEIVER_NOT_EXPORTED);
            } catch (Throwable t) {
                receptorBateria = null;
                XposedBridge.log(t);
            }
        } else if (receptorBateria != null) {
            try {
                ctx.unregisterReceiver(receptorBateria);
            } catch (Throwable ignorado) {
                // ya estaba desregistrado
            }
            receptorBateria = null;
        }
    }

    // ------------------------------------------------------------------ linterna, timbre, auriculares

    private static Evento evLinterna(boolean on) {
        Evento e = new Evento(Evento.LINTERNA);
        e.glifo = GlyphView.LINTERNA;
        e.activo = on;
        e.color = 0xFFFFD60A;
        e.titulo = "Linterna";
        e.texto = on ? "Encendida" : "Apagada";
        return e;
    }

    private static void linterna(boolean on) {
        if (SystemClock.uptimeMillis() < ignoraLinternaHasta) {
            ultimaLinterna = on;
            return;
        }
        if (ultimaLinterna != null && ultimaLinterna == on) return;
        ultimaLinterna = on;
        isla().show(evLinterna(on));
    }

    private static Evento evTimbre(int modo) {
        Evento e = new Evento(Evento.TIMBRE);
        e.glifo = GlyphView.CAMPANA;
        e.sub = modo;
        e.color = NARANJA;
        e.titulo = modo == AudioManager.RINGER_MODE_SILENT ? "Silencio"
                : (modo == AudioManager.RINGER_MODE_VIBRATE ? "Vibración" : "Timbre");
        e.texto = "Modo de sonido";
        return e;
    }

    private static void timbre(int modo) {
        if (modo < 0 || modo == ultimoModo) return;
        ultimoModo = modo;
        isla().show(evTimbre(modo));
    }

    private static Evento evAuric(CharSequence titulo, CharSequence texto) {
        Evento e = new Evento(Evento.AURIC);
        e.glifo = GlyphView.AURICULARES;
        e.color = AZUL;
        e.titulo = titulo;
        e.texto = texto;
        return e;
    }

    private static boolean repetido(String clave) {
        long ahora = SystemClock.uptimeMillis();
        if (clave.equals(ultimaClave) && ahora - ultimaClaveHora < 2500) return true;
        ultimaClave = clave;
        ultimaClaveHora = ahora;
        return false;
    }

    private static void cableAuriculares(boolean conectados) {
        if (repetido("cable" + conectados)) return;
        isla().show(evAuric("Auriculares", conectados ? "Conectados" : "Desconectados"));
    }

    private static void bluetooth(Intent i, boolean conectado) {
        String nombre = null;
        try {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice.class);
            if (d != null) nombre = d.getName();
        } catch (Throwable ignorado) {
            // sin permiso o sin nombre: se muestra un titulo generico
        }
        if (repetido("bt" + nombre + conectado)) return;
        isla().show(evAuric(nombre != null ? nombre : "Bluetooth",
                conectado ? "Conectado" : "Desconectado"));
    }

    // ------------------------------------------------------------------ musica

    /** Llega una notificacion con sesion de medios: se engancha a la sesion. */
    static void media(final StatusBarNotification sbn, final MediaSession.Token token) {
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String pkg = sbn.getPackageName();
                    Rastreo r = RASTREOS.get(pkg);
                    if (r != null && r.token.equals(token)) {
                        r.accion = sbn.getNotification().contentIntent;
                        return;
                    }
                    if (r != null) r.detener();
                    r = new Rastreo(pkg, token, sbn.getNotification().contentIntent);
                    RASTREOS.put(pkg, r);
                    r.mc.registerCallback(r, h);
                    r.actualizar();
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    static void mediaRemovida(final String pkg) {
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                Rastreo r = RASTREOS.remove(pkg);
                if (r != null) r.detener();
                if (RASTREOS.isEmpty()) isla().setMusica(null, false);
            }
        });
    }

    static MediaSession.Token tokenDe(Notification n) {
        try {
            if (n.extras == null) return null;
            return n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Rastreo extends MediaController.Callback {
        final String pkg;
        final MediaSession.Token token;
        final MediaController mc;
        android.app.PendingIntent accion;
        Drawable iconoApp;
        String ultimoTitulo;
        final Runnable pausa = new Runnable() {
            @Override
            public void run() {
                // Sigue en pausa pasados unos segundos: se quita la capsula
                isla().setMusica(null, false);
            }
        };

        Rastreo(String pkg, MediaSession.Token token, android.app.PendingIntent accion) {
            this.pkg = pkg;
            this.token = token;
            this.accion = accion;
            this.mc = new MediaController(ctx, token);
            try {
                PackageManager pm = ctx.getPackageManager();
                iconoApp = pm.getApplicationIcon(pkg);
            } catch (Throwable ignorado) {
                iconoApp = null;
            }
        }

        void detener() {
            try {
                mc.unregisterCallback(this);
            } catch (Throwable ignorado) {
                // ya estaba desregistrado
            }
            h.removeCallbacks(pausa);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            actualizar();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            actualizar();
        }

        @Override
        public void onSessionDestroyed() {
            RASTREOS.remove(pkg);
            detener();
            if (RASTREOS.isEmpty()) isla().setMusica(null, false);
        }

        void actualizar() {
            try {
                MediaMetadata md = mc.getMetadata();
                PlaybackState s = mc.getPlaybackState();
                if (md == null) return;
                String titulo = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (titulo == null || titulo.isEmpty()) return;
                String artista = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artista == null) artista = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                if (artista == null) artista = md.getString(MediaMetadata.METADATA_KEY_ALBUM);

                Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

                boolean suena = s != null && (s.getState() == PlaybackState.STATE_PLAYING
                        || s.getState() == PlaybackState.STATE_BUFFERING);

                Evento e = new Evento(Evento.MUSICA);
                e.titulo = titulo;
                e.texto = artista != null ? artista : "";
                e.icono = bmp != null ? new BitmapDrawable(ctx.getResources(), bmp) : iconoApp;
                e.color = ROSA;
                e.control = mc;
                e.accion = accion;
                e.reproduciendo = suena;
                long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                e.duracion = dur > 0 ? dur : 0;
                if (s != null) {
                    e.posicion = Math.max(0, s.getPosition());
                    e.velocidad = s.getPlaybackSpeed();
                    e.marca = s.getLastPositionUpdateTime();
                }

                boolean cambio = !titulo.equals(ultimoTitulo);
                ultimoTitulo = titulo;
                h.removeCallbacks(pausa);
                if (suena) {
                    isla().setMusica(e, cambio);
                } else {
                    isla().setMusica(e, false);
                    h.postDelayed(pausa, 5000);
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    // ------------------------------------------------------------------ pruebas

    private static final BroadcastReceiver prueba = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                int tipo = i.getIntExtra("tipo", 0);
                int nivel = Math.max(0, Math.min(100, i.getIntExtra("nivel", 72)));
                switch (tipo) {
                    case 1: {
                        Evento e = evCarga(bateriaActual());
                        e.nivel = nivel;
                        isla().show(e);
                        break;
                    }
                    case 2:
                        isla().show(evLinterna(true));
                        break;
                    case 3:
                        isla().show(evLinterna(false));
                        break;
                    case 4: {
                        Evento e = new Evento(Evento.MUSICA);
                        e.titulo = "Canción de prueba";
                        e.texto = "Artista de prueba";
                        e.color = ROSA;
                        e.reproduciendo = true;
                        e.duracion = 180000;
                        e.posicion = 42000;
                        e.velocidad = 1f;
                        e.marca = SystemClock.elapsedRealtime();
                        isla().setMusica(e, true);
                        break;
                    }
                    case 5:
                        isla().show(evTimbre(AudioManager.RINGER_MODE_SILENT));
                        break;
                    case 6:
                        isla().show(evAuric("Auriculares de prueba", "Conectados"));
                        break;
                    case 7: {
                        Evento e = new Evento(Evento.BATERIA);
                        e.glifo = GlyphView.BATERIA;
                        e.nivel = 14;
                        e.color = ROJO;
                        e.titulo = "Batería baja";
                        e.texto = "14% restante";
                        isla().show(e);
                        break;
                    }
                    case 8: {
                        Evento e = new Evento(Evento.BATERIA);
                        e.glifo = GlyphView.CHECK;
                        e.nivel = 100;
                        e.color = VERDE;
                        e.titulo = "Batería cargada";
                        e.texto = "100%";
                        isla().show(e);
                        break;
                    }
                    case 9:
                        isla().setMusica(null, false);
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };
}
