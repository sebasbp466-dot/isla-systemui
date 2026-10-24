package com.lancelot.island;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Locale;

/**
 * Ajustes de la isla. Se guardan como un texto en Settings.Secure; SystemUI los lee
 * una vez y los mantiene en cache, y solo vuelve a leerlos cuando cambian (ContentObserver).
 */
final class IslaConfig {

    static final String KEY = "isla_config";

    boolean enabled = true;
    int widthDp = 320;
    int heightDp = 64;
    /** Ajuste vertical extra de la isla expandida, en dp. */
    int topDp = 2;
    int radiusDp = 32;
    int durationMs = 4000;
    int bgColor = 0xFF000000;
    int titleColor = 0xFFFFFFFF;
    int bodyColor = 0xFFCCCCCC;
    boolean bounce = true;
    boolean showIcon = true;
    String blocked = "";
    /** 0 = Hyper (negra solida), 1 = Cristal (translucida con brillo) */
    int style = 0;
    /** Ancho minimo de la capsula compacta, en dp. */
    int compactW = 110;
    /** 0 = la expandida baja bajo la barra de estado, 1 = se superpone a la barra. */
    int expandedPos = 0;

    // Que eventos muestra la isla
    boolean fNotif = true;
    boolean fCarga = true;
    boolean fLinterna = true;
    boolean fMusica = true;
    boolean fTimbre = true;
    boolean fAuric = true;
    boolean fBateria = true;

    private static volatile IslaConfig cache;
    private static boolean observando;

    /** Version con cache para SystemUI: lee una vez y se actualiza solo al cambiar los ajustes. */
    static IslaConfig get(final Context c) {
        IslaConfig x = cache;
        if (x == null) {
            x = load(c);
            cache = x;
            observar(c);
        }
        return x;
    }

    private static synchronized void observar(final Context c) {
        if (observando) return;
        observando = true;
        try {
            c.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(KEY), false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            cache = load(c);
                        }
                    });
        } catch (Throwable t) {
            observando = false;
        }
    }

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

    boolean permite(int tipo) {
        switch (tipo) {
            case Evento.NOTIF:
                return fNotif;
            case Evento.CARGA:
                return fCarga;
            case Evento.LINTERNA:
                return fLinterna;
            case Evento.MUSICA:
                return fMusica;
            case Evento.TIMBRE:
                return fTimbre;
            case Evento.AURIC:
                return fAuric;
            case Evento.BATERIA:
                return fBateria;
            default:
                return true;
        }
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
                    case "st":
                        style = clamp(Integer.parseInt(v), 0, 1);
                        break;
                    case "cm":
                        compactW = clamp(Integer.parseInt(v), 90, 200);
                        break;
                    case "ep":
                        expandedPos = clamp(Integer.parseInt(v), 0, 1);
                        break;
                    case "xn":
                        fNotif = "1".equals(v);
                        break;
                    case "xc":
                        fCarga = "1".equals(v);
                        break;
                    case "xl":
                        fLinterna = "1".equals(v);
                        break;
                    case "xm":
                        fMusica = "1".equals(v);
                        break;
                    case "xt":
                        fTimbre = "1".equals(v);
                        break;
                    case "xb":
                        fAuric = "1".equals(v);
                        break;
                    case "xa":
                        fBateria = "1".equals(v);
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
        return "en=" + b(enabled)
                + ";w=" + widthDp
                + ";h=" + heightDp
                + ";t=" + topDp
                + ";r=" + radiusDp
                + ";d=" + durationMs
                + ";bg=" + hex(bgColor)
                + ";tc=" + hex(titleColor)
                + ";bc=" + hex(bodyColor)
                + ";bo=" + b(bounce)
                + ";ic=" + b(showIcon)
                + ";st=" + style
                + ";cm=" + compactW
                + ";ep=" + expandedPos
                + ";xn=" + b(fNotif)
                + ";xc=" + b(fCarga)
                + ";xl=" + b(fLinterna)
                + ";xm=" + b(fMusica)
                + ";xt=" + b(fTimbre)
                + ";xb=" + b(fAuric)
                + ";xa=" + b(fBateria)
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

    private static String b(boolean v) {
        return v ? "1" : "0";
    }

    private static String hex(int c) {
        return String.format(Locale.ROOT, "%08X", c);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
