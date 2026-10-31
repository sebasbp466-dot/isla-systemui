package com.lancelot.island;

import android.util.Log;

/**
 * Registro que funciona dentro y fuera de LSPosed: dentro de SystemUI escribe en el registro del
 * modulo (XposedBridge) y, si esa clase no existe (la vista previa en el emulador), en logcat.
 */
final class Registro {

    private static final String ETIQUETA = "IslaSystemUI";

    private Registro() {
    }

    static void log(String texto) {
        try {
            de.robv.android.xposed.XposedBridge.log(texto);
        } catch (Throwable t) {
            Log.w(ETIQUETA, texto);
        }
    }

    static void log(Throwable e) {
        try {
            de.robv.android.xposed.XposedBridge.log(e);
        } catch (Throwable t) {
            Log.w(ETIQUETA, e);
        }
    }
}
