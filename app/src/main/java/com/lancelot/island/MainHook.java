package com.lancelot.island;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
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
                            if (Eventos.tokenDe(sbn.getNotification()) != null) {
                                Eventos.mediaRemovida(sbn.getPackageName());
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
        ev.titulo = titulo;
        ev.texto = texto != null ? texto : "";
        ev.icono = icono;
        PendingIntent accion = n.contentIntent;
        ev.accion = accion;
        IslandController.get(ctx).show(ev);
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
