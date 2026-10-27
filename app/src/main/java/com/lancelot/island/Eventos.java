package com.lancelot.island;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XposedBridge;

/**
 * Fuentes de eventos de la isla. Todo es por receptores y callbacks (nada de sondeo):
 * si no pasa nada, no se ejecuta nada.
 */
final class Eventos {

    static final String ACCION_PRUEBA = "com.lancelot.island.TEST";
    private static final String PERMISO_PRUEBA = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String TAG = "[IslaSystemUI] ";
    private static final String ACCION_VOLUMEN = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_TIPO_VOL = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALOR_VOL = "android.media.EXTRA_VOLUME_STREAM_VALUE";

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
    private static String torchId;
    private static long linternaBase;
    private static int nivelLinterna = 1;
    private static Runnable autoApagar;
    private static Boolean ultimoAvion;
    private static Boolean ultimoNoMolestar;
    private static Boolean ultimoAhorro;
    private static Boolean ultimoWifi;
    private static Boolean ultimoBt;
    private static boolean calienteAvisado;
    private static boolean camaraActiva;
    private static boolean microActivo;
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
            isla().pantalla(true);
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
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        f.addAction(ACCION_VOLUMEN);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        ctx.registerReceiver(receptor, f, null, h, Context.RECEIVER_NOT_EXPORTED);

        iniciarPrivacidad();
        iniciarPortapapeles();

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
                    if (enabled) torchId = cameraId;
                    linterna(enabled);
                }

                @Override
                public void onTorchStrengthLevelChanged(String cameraId, int nivel) {
                    // La intensidad cambio (desde la isla o desde otro sitio)
                    nivelLinterna = nivel;
                    if (ultimaLinterna != null && ultimaLinterna) {
                        isla().setLinterna(evLinterna(true), false);
                    }
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
                    case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                        avion(i.getBooleanExtra("state", false));
                        break;
                    case NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED:
                        noMolestar();
                        break;
                    case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                        ahorro();
                        break;
                    case WifiManager.WIFI_STATE_CHANGED_ACTION:
                        wifi(i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1));
                        break;
                    case BluetoothAdapter.ACTION_STATE_CHANGED:
                        bluetoothEstado(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                        break;
                    case ACCION_VOLUMEN:
                        volumen(i.getIntExtra(EXTRA_TIPO_VOL, -1),
                                i.getIntExtra(EXTRA_VALOR_VOL, -1));
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
        double w = vatios(bat);
        if (w >= 15.0) {
            e.titulo = "Carga rápida";
            e.texto = e.texto + " \u00B7 " + Math.round(w) + " W";
        } else if (w >= 0.5) {
            e.texto = e.texto + " \u00B7 " + String.format(java.util.Locale.ROOT, "%.1f", w) + " W";
        }
        String resta = tiempoRestante();
        if (resta != null) e.texto = e.texto + " \u00B7 " + resta;
        return e;
    }

    /** Potencia de carga en vatios (corriente por voltaje); 0 si el telefono no lo informa. */
    private static double vatios(Intent bat) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            long ua = Math.abs((long) bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
            int mv = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) : 0;
            if (ua <= 0 || mv <= 0) return 0.0;
            // Algunos telefonos informan en mA y otros en uA
            double amperios = ua > 20000 ? ua / 1e6 : ua / 1e3;
            double w = amperios * (mv / 1000.0);
            return w > 0.5 && w < 150.0 ? w : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** "1 h 20 min para completar", o null si el sistema aun no lo sabe. */
    private static String tiempoRestante() {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            long ms = bm.computeChargeTimeRemaining();
            if (ms <= 0) return null;
            long min = Math.max(1, ms / 60000);
            if (min >= 60) {
                long hh = min / 60;
                long mm = min % 60;
                return hh + " h" + (mm > 0 ? " " + mm + " min" : "") + " para llenar";
            }
            return min + " min para llenar";
        } catch (Throwable t) {
            return null;
        }
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
                        int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                        if (temp >= 420 && !calienteAvisado) {
                            calienteAvisado = true;
                            Evento cal = new Evento(Evento.BATERIA);
                            cal.glifo = GlyphView.BATERIA;
                            cal.nivel = n;
                            cal.color = ROJO;
                            cal.titulo = "Batería caliente";
                            cal.texto = String.format(java.util.Locale.ROOT, "%.1f °C \u00B7 conviene dejar de cargar",
                                    temp / 10.0);
                            isla().show(cal);
                        } else if (temp < 400) {
                            calienteAvisado = false;
                        }
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

    /** Niveles de intensidad que admite el flash (1 si solo se enciende o apaga). */
    private static int maxIntensidad() {
        try {
            if (torchId == null) return 1;
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            Integer mx = cm.getCameraCharacteristics(torchId).get(
                    CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            return mx == null ? 1 : mx;
        } catch (Throwable t) {
            return 1;
        }
    }

    private static void apagarLinterna() {
        try {
            if (torchId == null) return;
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            cm.setTorchMode(torchId, false);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void cambiarIntensidad() {
        try {
            int mx = maxIntensidad();
            if (torchId == null || mx <= 1) return;
            int siguiente = nivelLinterna % mx + 1;
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            cm.turnOnTorchWithStrengthLevel(torchId, siguiente);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static Evento evLinterna(boolean on) {
        Evento e = new Evento(Evento.LINTERNA);
        e.glifo = GlyphView.LINTERNA;
        e.activo = on;
        e.color = 0xFFFFD60A;
        e.titulo = "Linterna";
        e.texto = on ? "Encendida" : "Apagada";
        if (on) {
            e.base = linternaBase > 0 ? linternaBase : SystemClock.elapsedRealtime();
            int mx = maxIntensidad();
            if (mx > 1) {
                e.texto = "Encendida \u00B7 intensidad " + nivelLinterna + "/" + mx;
                e.botonTxt = new String[]{"Apagar", "Intensidad"};
                e.botonColor = new int[]{0xFFFF453A, 0x33FFFFFF};
                e.botonRun = new Runnable[]{new Runnable() {
                    @Override
                    public void run() {
                        apagarLinterna();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        cambiarIntensidad();
                    }
                }};
            } else {
                e.botonTxt = new String[]{"Apagar"};
                e.botonColor = new int[]{0xFFFF453A};
                e.botonRun = new Runnable[]{new Runnable() {
                    @Override
                    public void run() {
                        apagarLinterna();
                    }
                }};
            }
        }
        return e;
    }

    private static void programarAutoApagado() {
        cancelarAutoApagado();
        int min = IslaConfig.get(ctx).linternaAutoMin;
        if (min <= 0) return;
        autoApagar = new Runnable() {
            @Override
            public void run() {
                apagarLinterna();
            }
        };
        h.postDelayed(autoApagar, min * 60000L);
    }

    private static void cancelarAutoApagado() {
        if (autoApagar != null) {
            h.removeCallbacks(autoApagar);
            autoApagar = null;
        }
    }

    private static void linterna(boolean on) {
        if (SystemClock.uptimeMillis() < ignoraLinternaHasta) {
            // Estado inicial al arrancar: si ya estaba encendida, la capsula queda sin anunciarse
            ultimaLinterna = on;
            if (on) {
                linternaBase = SystemClock.elapsedRealtime();
                programarAutoApagado();
                isla().setLinterna(evLinterna(true), false);
            }
            return;
        }
        if (ultimaLinterna != null && ultimaLinterna == on) return;
        ultimaLinterna = on;
        if (on) {
            linternaBase = SystemClock.elapsedRealtime();
            nivelLinterna = 1;
            programarAutoApagado();
            // La capsula se queda mientras este encendida
            isla().setLinterna(evLinterna(true), true);
        } else {
            cancelarAutoApagado();
            isla().setLinterna(null, false);
            isla().show(evLinterna(false));
        }
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

    // ------------------------------------------------------------------ ajustes rapidos

    private static Evento evAjuste(int glifo, int color, boolean activo, String titulo,
                                   String texto) {
        Evento e = new Evento(Evento.AJUSTE);
        e.glifo = glifo;
        e.color = color;
        e.activo = activo;
        e.titulo = titulo;
        e.texto = texto;
        e.duracionMs = 2400;
        return e;
    }

    private static void avion(boolean on) {
        if (ultimoAvion != null && ultimoAvion == on) return;
        ultimoAvion = on;
        isla().show(evAjuste(GlyphView.AVION, 0xFF64D2FF, on, "Modo avión",
                on ? "Activado" : "Desactivado"));
    }

    private static void wifi(int estado) {
        if (estado != WifiManager.WIFI_STATE_ENABLED && estado != WifiManager.WIFI_STATE_DISABLED) {
            return;
        }
        boolean on = estado == WifiManager.WIFI_STATE_ENABLED;
        if (ultimoWifi == null) {
            // El primer aviso es el estado inicial, no un cambio
            ultimoWifi = on;
            return;
        }
        if (ultimoWifi == on) return;
        ultimoWifi = on;
        isla().show(evAjuste(GlyphView.WIFI, AZUL, on, "Wi-Fi", on ? "Activado" : "Desactivado"));
    }

    private static void bluetoothEstado(int estado) {
        if (estado != BluetoothAdapter.STATE_ON && estado != BluetoothAdapter.STATE_OFF) return;
        boolean on = estado == BluetoothAdapter.STATE_ON;
        if (ultimoBt == null) {
            ultimoBt = on;
            return;
        }
        if (ultimoBt == on) return;
        ultimoBt = on;
        isla().show(evAjuste(GlyphView.BLUETOOTH, AZUL, on, "Bluetooth",
                on ? "Activado" : "Desactivado"));
    }

    private static void noMolestar() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(
                Context.NOTIFICATION_SERVICE);
        boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL
                && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        if (ultimoNoMolestar != null && ultimoNoMolestar == on) return;
        ultimoNoMolestar = on;
        isla().show(evAjuste(GlyphView.LUNA, 0xFFBF5AF2, on, "No molestar",
                on ? "Activado" : "Desactivado"));
    }

    private static void ahorro() {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean on = pm.isPowerSaveMode();
        if (ultimoAhorro != null && ultimoAhorro == on) return;
        ultimoAhorro = on;
        isla().show(evAjuste(GlyphView.AHORRO, NARANJA, on, "Ahorro de batería",
                on ? "Activado" : "Desactivado"));
    }

    private static Evento evVolumen(int tipo, int valor) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(tipo));
        Evento e = new Evento(Evento.VOLUMEN);
        e.glifo = GlyphView.VOLUMEN;
        e.nivel = Math.round(Math.max(0, valor) * 100f / max);
        e.color = 0xFFFFFFFF;
        e.clave = "vol";
        e.duracionMs = 1800;
        switch (tipo) {
            case AudioManager.STREAM_RING:
                e.titulo = "Timbre";
                break;
            case AudioManager.STREAM_ALARM:
                e.titulo = "Alarma";
                break;
            case AudioManager.STREAM_VOICE_CALL:
                e.titulo = "Llamada";
                break;
            case AudioManager.STREAM_NOTIFICATION:
                e.titulo = "Notificaciones";
                break;
            default:
                e.titulo = "Volumen";
                break;
        }
        e.texto = "";
        return e;
    }

    private static void volumen(int tipo, int valor) {
        if (tipo < 0 || valor < 0) return;
        if (!IslaConfig.get(ctx).fVolumen) return;
        isla().show(evVolumen(tipo, valor));
    }

    // ------------------------------------------------------------------ privacidad y portapapeles

    private static void iniciarPrivacidad() {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            ops.startWatchingActive(
                    new String[]{AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO},
                    new Executor() {
                        @Override
                        public void execute(Runnable r) {
                            h.post(r);
                        }
                    },
                    new AppOpsManager.OnOpActiveChangedListener() {
                        @Override
                        public void onOpActiveChanged(String op, int uid, String paquete,
                                                      boolean activo) {
                            privacidad(op, paquete, activo);
                        }
                    });
        } catch (Throwable t) {
            // Sin el permiso WATCH_APPOPS no hay aviso de camara y microfono
            XposedBridge.log(TAG + "sin aviso de camara/microfono: " + t);
        }
    }

    private static void privacidad(String op, String paquete, boolean activo) {
        try {
            boolean camara = AppOpsManager.OPSTR_CAMERA.equals(op);
            if (camara) {
                if (camaraActiva == activo) return;
                camaraActiva = activo;
            } else {
                if (microActivo == activo) return;
                microActivo = activo;
            }
            if (!activo) return;
            if (repetido("priv" + op)) return;
            Evento e = new Evento(Evento.PRIVACIDAD);
            e.glifo = camara ? GlyphView.CAMARA : GlyphView.MICROFONO;
            e.color = camara ? VERDE : NARANJA;
            e.activo = true;
            e.titulo = camara ? "Cámara en uso" : "Micrófono en uso";
            e.texto = paquete != null ? etiquetaApp(paquete) : "";
            e.minimizada = true;
            e.duracionMs = 3500;
            isla().show(e);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    static String etiquetaApp(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static void iniciarPortapapeles() {
        try {
            final ClipboardManager cm = (ClipboardManager) ctx.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            cm.addPrimaryClipChangedListener(new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    try {
                        if (!IslaConfig.get(ctx).fPortapapeles) return;
                        ClipData d = cm.getPrimaryClip();
                        if (d == null || d.getItemCount() == 0) return;
                        if (repetido("clip")) return;
                        Evento e = new Evento(Evento.PORTAPAPELES);
                        e.glifo = GlyphView.PORTAPAPELES;
                        e.color = AZUL;
                        e.titulo = "Copiado";
                        // Nunca se muestra el contenido: podria ser una contraseña
                        CharSequence t = d.getItemAt(0).getText();
                        e.texto = t != null ? t.length() + " caracteres" : "Contenido copiado";
                        e.duracionMs = 1800;
                        isla().show(e);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
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

    /** Color de acento de la caratula: su color medio, con mas viveza para que se vea sobre negro. */
    private static int acento(Bitmap b) {
        try {
            // Color dominante: se agrupan los pixeles por tono y gana el tono mas vistoso
            Bitmap p = Bitmap.createScaledBitmap(b, 24, 24, true);
            float[] peso = new float[12];
            float[] sat = new float[12];
            float[] val = new float[12];
            float[] hsv = new float[3];
            float gris = 0f;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    Color.colorToHSV(p.getPixel(x, y), hsv);
                    if (hsv[1] < 0.18f || hsv[2] < 0.18f) {
                        gris += 1f;
                        continue;
                    }
                    int k = Math.min(11, (int) (hsv[0] / 30f));
                    float w = hsv[1] * hsv[2];
                    peso[k] += w;
                    sat[k] += hsv[1] * w;
                    val[k] += hsv[2] * w;
                }
            }
            if (p != b) p.recycle();
            int mejor = -1;
            for (int k = 0; k < 12; k++) {
                // un tono vecino tambien suma, para no partir un color en dos grupos
                float t = peso[k] + 0.5f * (peso[(k + 1) % 12] + peso[(k + 11) % 12]);
                if (mejor < 0 || t > peso[mejor] + 0.5f * (peso[(mejor + 1) % 12]
                        + peso[(mejor + 11) % 12])) {
                    mejor = k;
                }
            }
            if (mejor < 0 || peso[mejor] < 3f) {
                return gris > 300f ? 0xFFEDEDF2 : ROSA; // caratula en blanco y negro
            }
            // Tono medio del grupo ganador, mas vivo para que se vea sobre negro
            float tono = mejor * 30f + 15f;
            float s2 = Math.max(0.6f, sat[mejor] / peso[mejor]);
            float v2 = Math.max(0.92f, val[mejor] / peso[mejor]);
            return Color.HSVToColor(new float[]{tono, Math.min(1f, s2), Math.min(1f, v2)});
        } catch (Throwable t) {
            return ROSA;
        }
    }

    private static final class Rastreo extends MediaController.Callback {
        final String pkg;
        final MediaSession.Token token;
        final MediaController mc;
        android.app.PendingIntent accion;
        Drawable iconoApp;
        String ultimoTitulo;
        int ultimoColor = ROSA;
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
                boolean cambio = !titulo.equals(ultimoTitulo);
                if (cambio) ultimoColor = bmp != null ? acento(bmp) : ROSA;
                e.color = ultimoColor;
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
                        linternaBase = SystemClock.elapsedRealtime();
                        isla().setLinterna(evLinterna(true), true);
                        break;
                    case 3:
                        isla().setLinterna(null, false);
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
                    case 10: {
                        Evento e = evLlamadaPrueba(1);
                        isla().setLlamada(e, true);
                        break;
                    }
                    case 11: {
                        Evento e = evLlamadaPrueba(2);
                        isla().setLlamada(e, true);
                        break;
                    }
                    case 12:
                        isla().setLlamada(null, false);
                        break;
                    case 13: {
                        Evento e = new Evento(Evento.PROGRESO);
                        e.glifo = GlyphView.DESCARGA;
                        e.activo = true;
                        e.color = AZUL;
                        e.clave = "prueba-descarga";
                        e.nivel = nivel;
                        e.titulo = "archivo.zip";
                        e.texto = nivel + "% \u00B7 Descargando";
                        isla().progreso(e, true);
                        break;
                    }
                    case 14:
                        privacidad(AppOpsManager.OPSTR_CAMERA, "com.android.camera", true);
                        camaraActiva = false;
                        break;
                    case 15:
                        privacidad(AppOpsManager.OPSTR_RECORD_AUDIO, "com.android.recorder",
                                true);
                        microActivo = false;
                        break;
                    case 16:
                        isla().show(evAjuste(GlyphView.AVION, 0xFF64D2FF, true, "Modo avión",
                                "Activado"));
                        break;
                    case 17:
                        isla().show(evAjuste(GlyphView.LUNA, 0xFFBF5AF2, true, "No molestar",
                                "Activado"));
                        break;
                    case 18:
                        isla().show(evAjuste(GlyphView.AHORRO, NARANJA, true,
                                "Ahorro de batería", "Activado"));
                        break;
                    case 19: {
                        Evento e = new Evento(Evento.VOLUMEN);
                        e.glifo = GlyphView.VOLUMEN;
                        e.nivel = nivel;
                        e.color = 0xFFFFFFFF;
                        e.clave = "vol";
                        e.titulo = "Volumen";
                        e.duracionMs = 1800;
                        isla().show(e);
                        break;
                    }
                    case 20: {
                        Evento e = evNotifPrueba("Ana", "Hola, ¿nos vemos hoy a las 6?");
                        e.minimizada = true;
                        isla().show(e);
                        break;
                    }
                    case 21: {
                        Evento e = evNotifPrueba("Carlos",
                                "Te mandé las fotos del viaje, mira cuando puedas y dime qué te parecen");
                        isla().show(e);
                        break;
                    }
                    case 23:
                        isla().show(evAjuste(GlyphView.WIFI, AZUL, true, "Wi-Fi", "Activado"));
                        break;
                    case 24:
                        isla().show(evAjuste(GlyphView.BLUETOOTH, AZUL, true, "Bluetooth",
                                "Activado"));
                        break;
                    case 25: {
                        Evento e = new Evento(Evento.BATERIA);
                        e.glifo = GlyphView.BATERIA;
                        e.nivel = 63;
                        e.color = ROJO;
                        e.titulo = "Batería caliente";
                        e.texto = "43.5 °C \u00B7 conviene dejar de cargar";
                        isla().show(e);
                        break;
                    }
                    case 26: {
                        Evento e = evCarga(bateriaActual());
                        e.titulo = "Carga rápida";
                        e.texto = "Cargador de pared \u00B7 18 W \u00B7 35 min para llenar";
                        e.nivel = nivel;
                        isla().show(e);
                        break;
                    }
                    case 27: {
                        Evento e = new Evento(Evento.CRONO);
                        e.glifo = GlyphView.RELOJ;
                        e.activo = true;
                        e.color = 0xFFFF9F0A;
                        e.clave = "prueba_crono";
                        e.titulo = "Temporizador";
                        e.texto = "Pasta \u00B7 quedan 3 min";
                        e.cuentaAtras = true;
                        e.base = android.os.SystemClock.elapsedRealtime() + 180000L;
                        e.botonTxt = new String[]{"Detener"};
                        e.botonColor = new int[]{0xFFFF453A};
                        e.botonRun = new Runnable[]{new Runnable() {
                            @Override
                            public void run() {
                                isla().setCrono(null, false);
                            }
                        }};
                        isla().setCrono(e, true);
                        break;
                    }
                    case 30: {
                        for (int k = 1; k <= 3; k++) {
                            Evento e = evNotifPrueba("Ana", "Mensaje número " + k);
                            e.app = "Mensajes";
                            e.pkg = "prueba.mensajes";
                            e.clave = "prueba_msg_" + k;
                            isla().show(e);
                        }
                        break;
                    }
                    case 28:
                        isla().setCrono(null, false);
                        break;
                    case 29: {
                        Evento e = evNotifPrueba("Ana", "Mira esta foto del viaje");
                        e.app = "Mensajes";
                        android.graphics.drawable.GradientDrawable gd =
                                new android.graphics.drawable.GradientDrawable(
                                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                                new int[]{0xFFFF9F0A, 0xFFBF5AF2});
                        e.imagen = gd;
                        isla().show(e);
                        break;
                    }
                    case 22: {
                        Evento e = new Evento(Evento.PORTAPAPELES);
                        e.glifo = GlyphView.PORTAPAPELES;
                        e.color = AZUL;
                        e.titulo = "Copiado";
                        e.texto = "24 caracteres";
                        e.duracionMs = 1800;
                        isla().show(e);
                        break;
                    }
                    default:
                        break;
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    };

    private static int contadorPrueba;

    private static Evento evNotifPrueba(String titulo, String texto) {
        Evento e = new Evento(Evento.NOTIF);
        e.glifo = GlyphView.MENSAJE;
        e.color = VERDE;
        e.titulo = titulo;
        e.texto = texto;
        e.clave = "prueba-notif-" + (contadorPrueba++);
        e.pkg = "com.lancelot.prueba";
        return e;
    }

    private static Evento evLlamadaPrueba(int sub) {
        Evento e = new Evento(Evento.LLAMADA);
        e.glifo = GlyphView.TELEFONO;
        e.color = VERDE;
        e.sub = sub;
        e.clave = "prueba-llamada";
        e.titulo = "Mamá";
        if (sub == 1) {
            e.activo = true;
            e.texto = "Llamada entrante";
            e.botonTxt = new String[]{"Rechazar", "Contestar"};
            e.botonColor = new int[]{0xFFFF453A, 0xFF34C759};
            e.botonRun = new Runnable[]{null, null};
        } else {
            e.texto = "Llamada en curso";
            e.base = SystemClock.elapsedRealtime() - 37000;
            e.botonTxt = new String[]{"Colgar"};
            e.botonColor = new int[]{0xFFFF453A};
            e.botonRun = new Runnable[]{new Runnable() {
                @Override
                public void run() {
                    isla().setLlamada(null, false);
                }
            }};
        }
        return e;
    }
}
