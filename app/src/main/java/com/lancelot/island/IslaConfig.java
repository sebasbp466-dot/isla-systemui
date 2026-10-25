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
    boolean showIcon = true;
    String blocked = "";
    /** 0 = Hyper (negra solida), 1 = Cristal (translucida con brillo) */
    int style = 0;
    /** Ancho minimo de la capsula compacta, en dp. */
    int compactW = 110;
    /** 0 = la expandida baja bajo la barra de estado, 1 = se superpone a la barra. */
    int expandedPos = 0;
    /** Movimiento: 0 = suave, 1 = resorte, 2 = elastico (estira y encoge). */
    int animStyle = 1;
    /** Velocidad de las animaciones en %, 100 = normal. */
    int speed = 100;
    /** Vibracion suave al expandirse. */
    boolean haptic = false;
    /** Aro de luz de color alrededor de la isla cuando llega un evento. */
    boolean glow = true;
    /** Luz interior (aura en Hyper, refraccion en Cristal). */
    boolean aura = true;
    /** Minutos tras los que se apaga sola la linterna (0 = nunca). */
    int linternaAutoMin = 0;
    /** Desenfoque del fondo del estilo Cristal, en dp (0 = sin desenfoque). */
    int blurDp = 24;
    /** No mostrar la isla con el telefono en horizontal. */
    boolean ocultarH = true;
    /** Notificaciones: 0 = siempre expandidas, 1 = siempre minimizadas, 2 = automatico. */
    int notifMode = 2;
    /** Apps cuyas notificaciones llegan siempre minimizadas (paquetes separados por coma). */
    String minimizadas = "";

    // Que eventos muestra la isla
    boolean fNotif = true;
    boolean fCarga = true;
    boolean fLinterna = true;
    boolean fMusica = true;
    boolean fTimbre = true;
    boolean fAuric = true;
    boolean fBateria = true;
    boolean fLlamada = true;
    boolean fProgreso = true;
    boolean fPrivacidad = true;
    boolean fAjustes = true;
    boolean fVolumen = false;
    boolean fPortapapeles = false;

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
            case Evento.LLAMADA:
                return fLlamada;
            case Evento.PROGRESO:
                return fProgreso;
            case Evento.PRIVACIDAD:
                return fPrivacidad;
            case Evento.AJUSTE:
                return fAjustes;
            case Evento.VOLUMEN:
                return fVolumen;
            case Evento.PORTAPAPELES:
                return fPortapapeles;
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
                    case "an":
                        animStyle = clamp(Integer.parseInt(v), 0, 2);
                        break;
                    case "vl":
                        speed = clamp(Integer.parseInt(v), 50, 200);
                        break;
                    case "hp":
                        haptic = "1".equals(v);
                        break;
                    case "gl":
                        glow = "1".equals(v);
                        break;
                    case "au":
                        aura = "1".equals(v);
                        break;
                    case "la":
                        linternaAutoMin = clamp(Integer.parseInt(v), 0, 60);
                        break;
                    case "br":
                        blurDp = clamp(Integer.parseInt(v), 0, 60);
                        break;
                    case "oh":
                        ocultarH = "1".equals(v);
                        break;
                    case "nm":
                        notifMode = clamp(Integer.parseInt(v), 0, 2);
                        break;
                    case "ml":
                        minimizadas = limpiar(v);
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
                    case "xk":
                        fLlamada = "1".equals(v);
                        break;
                    case "xg":
                        fProgreso = "1".equals(v);
                        break;
                    case "xy":
                        fPrivacidad = "1".equals(v);
                        break;
                    case "xj":
                        fAjustes = "1".equals(v);
                        break;
                    case "xv":
                        fVolumen = "1".equals(v);
                        break;
                    case "xp":
                        fPortapapeles = "1".equals(v);
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
                + ";ic=" + b(showIcon)
                + ";st=" + style
                + ";cm=" + compactW
                + ";ep=" + expandedPos
                + ";an=" + animStyle
                + ";vl=" + speed
                + ";hp=" + b(haptic)
                + ";gl=" + b(glow)
                + ";au=" + b(aura)
                + ";la=" + linternaAutoMin
                + ";br=" + blurDp
                + ";oh=" + b(ocultarH)
                + ";nm=" + notifMode
                + ";ml=" + limpiar(minimizadas)
                + ";xn=" + b(fNotif)
                + ";xc=" + b(fCarga)
                + ";xl=" + b(fLinterna)
                + ";xm=" + b(fMusica)
                + ";xt=" + b(fTimbre)
                + ";xb=" + b(fAuric)
                + ";xa=" + b(fBateria)
                + ";xk=" + b(fLlamada)
                + ";xg=" + b(fProgreso)
                + ";xy=" + b(fPrivacidad)
                + ";xj=" + b(fAjustes)
                + ";xv=" + b(fVolumen)
                + ";xp=" + b(fPortapapeles)
                + ";bl=" + limpiar(blocked);
    }

    boolean isBlocked(String pkg) {
        return enLista(blocked, pkg);
    }

    boolean isMin(String pkg) {
        return enLista(minimizadas, pkg);
    }

    static boolean enLista(String lista, String pkg) {
        if (pkg == null || lista == null || lista.isEmpty()) return false;
        for (String p : lista.split(",")) {
            if (p.trim().equals(pkg)) return true;
        }
        return false;
    }

    /** Devuelve la lista con el paquete añadido (sin repetirlo). */
    static String agregar(String lista, String pkg) {
        if (enLista(lista, pkg)) return lista;
        String nuevo = lista == null || lista.isEmpty() ? pkg : lista + "," + pkg;
        return limpiar(nuevo);
    }

    /** Duracion escalada por la velocidad de animacion elegida. */
    int ms(int base) {
        return Math.round(base * 100f / Math.max(30, speed));
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
