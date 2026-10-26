package com.lancelot.island;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Se engancha dentro de SystemUI: al listener de notificaciones que ya existe (sin servicios
 * ni procesos extra) y al arranque de la aplicacion para registrar los demas eventos.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[IslaSystemUI] ";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String LISTENER = "com.android.systemui.statusbar.NotificationListener";

    // key de notificacion -> hash del ultimo contenido (evita repetir actualizaciones iguales)
    private final HashMap<String, Integer> vistos = new HashMap<>();
    // key de notificacion con progreso -> porcentaje con el que se mostro la ultima vez
    private final HashMap<String, Integer> progresos = new HashMap<>();
    private int ultimaLlamadaSub = -1;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;
        try {
            // Notificaciones: publicadas y retiradas
            XC_MethodHook publicada = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0
                                && param.args[0] instanceof StatusBarNotification) {
                            Context ctx = contexto(param.thisObject);
                            if (ctx == null) {
                                XposedBridge.log(TAG + "sin contexto, no se puede mostrar");
                                return;
                            }
                            Eventos.iniciar(ctx);
                            manejar(ctx, (StatusBarNotification) param.args[0]);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };
            XC_MethodHook retirada = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0
                                && param.args[0] instanceof StatusBarNotification) {
                            StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                            Notification n = sbn.getNotification();
                            if (Eventos.tokenDe(n) != null) {
                                Eventos.mediaRemovida(sbn.getPackageName());
                            }
                            synchronized (progresos) {
                                progresos.remove(sbn.getKey());
                            }
                            if (cronoClave != null && cronoClave.equals(sbn.getKey())) {
                                Context cc = contexto(param.thisObject);
                                cronoClave = null;
                                if (cc != null) IslandController.get(cc).setCrono(null, false);
                            }
                            if (n != null && Notification.CATEGORY_CALL.equals(n.category)) {
                                Context ctx = contexto(param.thisObject);
                                if (ctx != null) {
                                    ultimaLlamadaSub = -1;
                                    IslandController.get(ctx).setLlamada(null, false);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };

            int hooks = 0;
            Class<?> c = XposedHelpers.findClassIfExists(LISTENER, lpparam.classLoader);
            if (c != null) {
                hooks = XposedBridge.hookAllMethods(c, "onNotificationPosted", publicada).size();
                XposedBridge.hookAllMethods(c, "onNotificationRemoved", retirada);
            }
            if (hooks == 0) {
                // Plan B: si en tu ROM la clase se llama distinto, engancha la clase base
                hooks = XposedBridge.hookAllMethods(
                        NotificationListenerService.class, "onNotificationPosted", publicada)
                        .size();
                XposedBridge.hookAllMethods(
                        NotificationListenerService.class, "onNotificationRemoved", retirada);
                XposedBridge.log(TAG + "usando plan B (clase base), hooks=" + hooks);
            } else {
                XposedBridge.log(TAG + "hooks=" + hooks);
            }

            // Eventos del sistema (carga, linterna, timbre...): se registran al crearse la app
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Context) {
                        Eventos.iniciar((Context) param.thisObject);
                    }
                }
            });
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app instanceof Context) Eventos.iniciar((Context) app);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void manejar(Context ctx, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return;
        if (SYSTEMUI.equals(sbn.getPackageName())) return;
        IslaConfig cfg = IslaConfig.get(ctx);
        if (!cfg.enabled) return;

        // Reproductor de musica: no es una notificacion, es una sesion de medios
        MediaSession.Token token = Eventos.tokenDe(n);
        if (token != null) {
            if (cfg.fMusica) Eventos.media(sbn, token);
            return;
        }

        // Llamadas: capsula fija con temporizador y botones
        if (Notification.CATEGORY_CALL.equals(n.category)) {
            if (cfg.fLlamada) llamada(ctx, sbn, n);
            return;
        }

        // Cronometros y temporizadores: capsula fija con cuenta
        Bundle exc = n.extras;
        if (cfg.fCrono && exc != null && exc.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
                && !cfg.isBlocked(sbn.getPackageName())) {
            crono(ctx, sbn, n, true);
            return;
        }

        // Navegacion y alarmas en curso: capsula fija con el texto
        if (cfg.fCrono && !cfg.isBlocked(sbn.getPackageName())
                && (Notification.CATEGORY_NAVIGATION.equals(n.category)
                || Notification.CATEGORY_ALARM.equals(n.category))) {
            crono(ctx, sbn, n, false);
            return;
        }

        // Descargas y demas progreso: aro en la capsula minimizada
        Bundle ex = n.extras;
        if (ex != null && cfg.fProgreso && !cfg.isBlocked(sbn.getPackageName())) {
            int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
            if (max > 0 && !ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) {
                progreso(ctx, sbn, n, ex.getInt(Notification.EXTRA_PROGRESS, 0), max);
                return;
            }
        }

        int ignorar = Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_GROUP_SUMMARY
                | Notification.FLAG_FOREGROUND_SERVICE;
        if ((n.flags & ignorar) != 0) return;
        if (!cfg.fNotif || cfg.isBlocked(sbn.getPackageName())) return;

        Bundle e = n.extras;
        CharSequence titulo = e != null ? e.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence texto = e != null ? e.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (isEmpty(titulo) && isEmpty(texto)) return;
        if (isEmpty(titulo)) titulo = etiquetaApp(ctx, sbn.getPackageName());

        String key = sbn.getKey();
        int hash = (String.valueOf(titulo) + "|" + texto).hashCode();
        synchronized (vistos) {
            Integer prev = vistos.get(key);
            if (prev != null && prev == hash) return;
            if (vistos.size() > 100) vistos.clear();
            vistos.put(key, hash);
        }

        Drawable icono = null;
        try {
            Icon grande = n.getLargeIcon();
            if (grande != null) {
                icono = grande.loadDrawable(ctx);
            } else {
                Icon chico = n.getSmallIcon();
                if (chico != null) {
                    icono = chico.loadDrawable(ctx);
                    if (icono != null) {
                        icono = icono.mutate();
                        icono.setTint(Color.WHITE);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        Evento ev = new Evento(Evento.NOTIF);
        ev.app = etiquetaApp(ctx, sbn.getPackageName());
        try {
            Object pic = e != null ? e.get(Notification.EXTRA_PICTURE) : null;
            if (pic instanceof Bitmap) {
                Bitmap b = (Bitmap) pic;
                int mx = Math.max(b.getWidth(), b.getHeight());
                if (mx > 160) {
                    float s = 160f / mx;
                    b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                            Math.max(1, Math.round(b.getHeight() * s)), true);
                }
                ev.imagen = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), b);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        ev.titulo = titulo;
        ev.texto = texto != null ? texto : "";
        ev.icono = icono;
        PendingIntent accion = n.contentIntent;
        ev.accion = accion;
        ev.pkg = sbn.getPackageName();
        ev.clave = key;
        ev.color = acento(n.color);
        // Primera accion nativa que no pida escribir (marcar leido, archivar...)
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null || isEmpty(a.title)) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                ev.nativaTxt = a.title;
                ev.nativaPi = a.actionIntent;
                break;
            }
        }
        // Respuesta rapida desde la isla
        if (n.actions != null && ev.botonTxt == null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null) continue;
                android.app.RemoteInput[] ri = a.getRemoteInputs();
                if (ri == null || ri.length == 0) continue;
                ev.respPi = a.actionIntent;
                ev.respRi = ri;
                break;
            }
            if (ev.respPi != null) {
                final Context c2 = ctx;
                ev.botonTxt = new String[]{"Responder"};
                ev.botonColor = new int[]{0xFF2F7DF6};
                ev.botonRun = new Runnable[]{new Runnable() {
                    @Override
                    public void run() {
                        IslandController.get(c2).iniciarRespuesta();
                    }
                }};
            }
        }
        IslandController.get(ctx).show(ev);
    }

    /** Color de acento de la app, o blanco si es muy oscuro para verse sobre negro. */
    private static int acento(int color) {
        if (color == 0) return 0xFFFFFFFF;
        int c = color | 0xFF000000;
        float lum = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
                / 255f;
        return lum < 0.25f ? 0xFFFFFFFF : c;
    }

    private void progreso(Context ctx, StatusBarNotification sbn, Notification n, int prog,
                          int max) {
        Bundle e = n.extras;
        CharSequence titulo = e != null ? e.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence texto = e != null ? e.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (isEmpty(titulo)) titulo = etiquetaApp(ctx, sbn.getPackageName());
        int pct = Math.max(0, Math.min(100, Math.round(prog * 100f / max)));

        Evento ev = new Evento(Evento.PROGRESO);
        ev.glifo = GlyphView.DESCARGA;
        ev.activo = true;
        ev.color = 0xFF0A84FF;
        ev.clave = sbn.getKey();
        ev.pkg = sbn.getPackageName();
        ev.accion = n.contentIntent;
        ev.nivel = pct;
        ev.titulo = titulo;
        ev.texto = pct + "%" + (isEmpty(texto) ? "" : " \u00B7 " + texto);

        // Vuelve a aparecer solo si avanzo bastante; en medio solo se actualiza el aro
        boolean nuevo;
        synchronized (progresos) {
            Integer ultimo = progresos.get(sbn.getKey());
            nuevo = pct < 100 && (ultimo == null || pct - ultimo >= 25);
            if (nuevo) {
                if (progresos.size() > 50) progresos.clear();
                progresos.put(sbn.getKey(), pct);
            }
        }
        IslandController.get(ctx).progreso(ev, nuevo);
    }

    private static volatile String cronoClave;

    private void crono(Context ctx, StatusBarNotification sbn, Notification n,
                       boolean conReloj) {
        Bundle e = n.extras;
        CharSequence titulo = e.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence texto = e.getCharSequence(Notification.EXTRA_TEXT);
        boolean atras = conReloj
                && e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false);
        if (isEmpty(titulo)) titulo = etiquetaApp(ctx, sbn.getPackageName());
        Evento ev = new Evento(Evento.CRONO);
        boolean nav = Notification.CATEGORY_NAVIGATION.equals(n.category);
        ev.glifo = nav ? GlyphView.UBICACION : GlyphView.RELOJ;
        ev.activo = true;
        ev.color = acento(n.color) == 0xFFFFFFFF
                ? (nav ? 0xFF0A84FF : conReloj ? 0xFFFF9F0A : 0xFFFF453A) : acento(n.color);
        ev.clave = sbn.getKey();
        ev.pkg = sbn.getPackageName();
        ev.accion = n.contentIntent;
        ev.titulo = titulo;
        ev.texto = isEmpty(texto) ? (!conReloj ? "En curso"
                : atras ? "Temporizador" : "Cron\u00F3metro") : texto;
        ev.cuentaAtras = atras;
        if (conReloj) {
            long delta = n.when - System.currentTimeMillis();
            ev.base = SystemClock.elapsedRealtime() + (atras ? delta : -Math.max(0, -delta));
        }
        if (n.actions != null) {
            java.util.ArrayList<Notification.Action> ac = new java.util.ArrayList<>();
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null || isEmpty(a.title)) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                ac.add(a);
            }
            int cuantos = Math.min(2, ac.size());
            if (cuantos > 0) {
                ev.botonTxt = new String[cuantos];
                ev.botonColor = new int[cuantos];
                ev.botonRun = new Runnable[cuantos];
                for (int i = 0; i < cuantos; i++) {
                    final Notification.Action a = ac.get(i);
                    ev.botonTxt[i] = String.valueOf(a.title);
                    ev.botonColor[i] = esRechazo(a.title) ? 0xFFFF453A : 0xFF3A3A40;
                    ev.botonRun[i] = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                a.actionIntent.send();
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    };
                }
            }
        }
        boolean anunciar = !ev.clave.equals(cronoClave);
        cronoClave = ev.clave;
        IslandController.get(ctx).setCrono(ev, anunciar);
    }

    private void llamada(Context ctx, StatusBarNotification sbn, Notification n) {
        Bundle e = n.extras;
        CharSequence titulo = e != null ? e.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence texto = e != null ? e.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (isEmpty(titulo)) titulo = "Llamada";
        boolean crono = e != null && e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);

        // Botones de la notificacion (contestar, colgar...), sin los que piden escribir
        java.util.ArrayList<Notification.Action> acciones = new java.util.ArrayList<>();
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null || isEmpty(a.title)) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                acciones.add(a);
            }
        }

        Evento ev = new Evento(Evento.LLAMADA);
        ev.glifo = GlyphView.TELEFONO;
        ev.color = 0xFF34C759;
        ev.clave = sbn.getKey();
        ev.pkg = sbn.getPackageName();
        ev.accion = n.contentIntent;
        ev.titulo = titulo;
        if (crono) {
            ev.sub = 2;
            ev.texto = "Llamada en curso";
            long desde = n.when > 0 ? Math.max(0, System.currentTimeMillis() - n.when) : 0;
            ev.base = SystemClock.elapsedRealtime() - desde;
        } else if (acciones.size() >= 2) {
            ev.sub = 1;
            ev.activo = true;
            ev.texto = "Llamada entrante";
        } else {
            ev.sub = 3;
            ev.texto = isEmpty(texto) ? "Llamando\u2026" : texto;
        }

        int cuantos = Math.min(2, acciones.size());
        if (cuantos > 0) {
            ev.botonTxt = new String[cuantos];
            ev.botonColor = new int[cuantos];
            ev.botonRun = new Runnable[cuantos];
            for (int i = 0; i < cuantos; i++) {
                final Notification.Action a = acciones.get(i);
                ev.botonTxt[i] = String.valueOf(a.title);
                ev.botonColor[i] = esRechazo(a.title) ? 0xFFFF453A : 0xFF34C759;
                ev.botonRun[i] = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            a.actionIntent.send();
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                };
            }
        }

        boolean anunciar = ev.sub != ultimaLlamadaSub;
        ultimaLlamadaSub = ev.sub;
        IslandController.get(ctx).setLlamada(ev, anunciar);
    }

    private static boolean esRechazo(CharSequence t) {
        String s = String.valueOf(t).toLowerCase(java.util.Locale.ROOT);
        return s.contains("rech") || s.contains("decl") || s.contains("colg")
                || s.contains("hang") || s.contains("end") || s.contains("reject")
                || s.contains("termin") || s.contains("finaliz");
    }

    /**
     * El listener de SystemUI no es un Service "de verdad": su contexto base es null,
     * asi que no sirve como Context. Usamos la Application de SystemUI.
     */
    private static Context contexto(Object listener) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        try {
            Object c = XposedHelpers.getObjectField(listener, "mSystemContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignorado) {
            // sin plan C
        }
        return null;
    }

    private static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    private static CharSequence etiquetaApp(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return pkg;
        }
    }
}
