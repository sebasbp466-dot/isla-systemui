package com.lancelot.island;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
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
 * Se engancha al NotificationListener que ya existe dentro de SystemUI.
 * No crea procesos ni servicios: solo dibuja una ventana mientras la isla esta visible.
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
            XC_MethodHook cb = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        XposedBridge.log(TAG + "hook disparado, args=" + param.args.length);
                        if (param.args.length > 0
                                && param.args[0] instanceof StatusBarNotification) {
                            Context ctx = contexto(param.thisObject);
                            if (ctx == null) {
                                XposedBridge.log(TAG + "sin contexto, no se puede mostrar");
                                return;
                            }
                            manejar(ctx, (StatusBarNotification) param.args[0]);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };

            int hooks = 0;
            Class<?> c = XposedHelpers.findClassIfExists(LISTENER, lpparam.classLoader);
            if (c != null) {
                hooks = XposedBridge.hookAllMethods(c, "onNotificationPosted", cb).size();
            }
            if (hooks == 0) {
                // Plan B: si en tu ROM la clase se llama distinto, engancha la clase base
                hooks = XposedBridge.hookAllMethods(
                        NotificationListenerService.class, "onNotificationPosted", cb).size();
                XposedBridge.log(TAG + "usando plan B (clase base), hooks=" + hooks);
            } else {
                XposedBridge.log(TAG + "hooks=" + hooks);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void manejar(Context ctx, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return;
        int ignorar = Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_GROUP_SUMMARY
                | Notification.FLAG_FOREGROUND_SERVICE;
        XposedBridge.log(TAG + "recibida pkg=" + sbn.getPackageName() + " flags=" + n.flags);
        if ((n.flags & ignorar) != 0) {
            XposedBridge.log(TAG + "ignorada: en curso/grupo/servicio");
            return;
        }
        if (SYSTEMUI.equals(sbn.getPackageName())) {
            XposedBridge.log(TAG + "ignorada: es de SystemUI");
            return;
        }

        IslaConfig cfg = IslaConfig.load(ctx);
        if (!cfg.enabled) {
            XposedBridge.log(TAG + "ignorada: isla desactivada en los ajustes");
            return;
        }
        if (cfg.isBlocked(sbn.getPackageName())) {
            XposedBridge.log(TAG + "ignorada: app bloqueada " + sbn.getPackageName());
            return;
        }

        Bundle e = n.extras;
        CharSequence titulo = e != null ? e.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence texto = e != null ? e.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (isEmpty(titulo) && isEmpty(texto)) {
            XposedBridge.log(TAG + "ignorada: sin titulo ni texto");
            return;
        }
        if (isEmpty(titulo)) titulo = etiquetaApp(ctx, sbn.getPackageName());

        String key = sbn.getKey();
        int hash = (String.valueOf(titulo) + "|" + texto).hashCode();
        synchronized (vistos) {
            Integer prev = vistos.get(key);
            if (prev != null && prev == hash) {
                XposedBridge.log(TAG + "ignorada: repetida");
                return;
            }
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

        PendingIntent accion = n.contentIntent;
        XposedBridge.log(TAG + "mostrando isla: " + titulo);
        IslandController.get(ctx).show(cfg, titulo, texto, icono, accion);
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
