package com.lancelot.island;

import android.content.Context;
import android.provider.Settings;

import java.util.Locale;

/**
 * Ajustes de la isla. Se guardan como un texto en Settings.Secure, asi SystemUI
 * los lee directamente sin necesitar ningun proceso ni servicio extra.
 */
final class IslaConfig {

    static final String KEY = "isla_config";

    boolean enabled = true;
    int widthDp = 320;
    int heightDp = 64;
    int topDp = 6;
    int radiusDp = 32;
    int durationMs = 4500;
    int bgColor = 0xFF000000;
    int titleColor = 0xFFFFFFFF;
    int bodyColor = 0xFFCCCCCC;
    boolean bounce = true;
    boolean showIcon = true;
    String blocked = "";

    static IslaConfig load(Context c) {
        IslaConfig cfg = new IslaConfig();
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), KEY);
            if (s != null && s.length() > 0) cfg.parse(s);
        } catch (Throwable ignorado) {
            // se queda con los valores por defecto
        }
        return cfg;
    }

    void parse(String s) {
        for (String par : s.split(";")) {
            int i = par.indexOf('=');
            if (i <= 0) continue;
            String k = par.substring(0, i);
            String v = par.substring(i + 1);
            try {
                switch (k) {
                    case "en":
                        enabled = "1".equals(v);
                        break;
                    case "w":
                        widthDp = clamp(Integer.parseInt(v), 200, 360);
                        break;
                    case "h":
                        heightDp = clamp(Integer.parseInt(v), 48, 100);
                        break;
                    case "t":
                        topDp = clamp(Integer.parseInt(v), 0, 60);
                        break;
                    case "r":
                        radiusDp = clamp(Integer.parseInt(v), 0, 50);
                        break;
                    case "d":
                        durationMs = clamp(Integer.parseInt(v), 1500, 12000);
                        break;
                    case "bg":
                        bgColor = (int) Long.parseLong(v, 16);
                        break;
                    case "tc":
                        titleColor = (int) Long.parseLong(v, 16);
                        break;
                    case "bc":
                        bodyColor = (int) Long.parseLong(v, 16);
                        break;
                    case "bo":
                        bounce = "1".equals(v);
                        break;
                    case "ic":
                        showIcon = "1".equals(v);
                        break;
                    case "bl":
                        blocked = limpiar(v);
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException ignorado) {
                // valor roto: se ignora y se queda el de por defecto
            }
        }
    }

    String serialize() {
        return "en=" + (enabled ? 1 : 0)
                + ";w=" + widthDp
                + ";h=" + heightDp
                + ";t=" + topDp
                + ";r=" + radiusDp
                + ";d=" + durationMs
                + ";bg=" + hex(bgColor)
                + ";tc=" + hex(titleColor)
                + ";bc=" + hex(bodyColor)
                + ";bo=" + (bounce ? 1 : 0)
                + ";ic=" + (showIcon ? 1 : 0)
                + ";bl=" + limpiar(blocked);
    }

    boolean isBlocked(String pkg) {
        if (pkg == null || blocked.isEmpty()) return false;
        for (String p : blocked.split(",")) {
            if (p.trim().equals(pkg)) return true;
        }
        return false;
    }

    /** Guarda con el permiso WRITE_SECURE_SETTINGS; si no lo tiene, prueba con root. */
    static boolean save(Context c, IslaConfig cfg) {
        String v = cfg.serialize();
        try {
            return Settings.Secure.putString(c.getContentResolver(), KEY, v);
        } catch (SecurityException sinPermiso) {
            // sigue con el plan B
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "settings put secure " + KEY + " '" + v + "'"});
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Deja solo letras, numeros, punto, guion bajo y coma (seguro para pasar por la terminal). */
    static String limpiar(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == ',') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String hex(int c) {
        return String.format(Locale.ROOT, "%08X", c);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
