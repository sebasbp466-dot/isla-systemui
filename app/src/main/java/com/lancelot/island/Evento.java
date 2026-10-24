package com.lancelot.island;

import android.app.PendingIntent;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.os.SystemClock;

/** Algo que la isla puede mostrar: una notificacion, la carga, la linterna, la musica... */
final class Evento {

    static final int NOTIF = 0;
    static final int CARGA = 1;
    static final int LINTERNA = 2;
    static final int MUSICA = 3;
    static final int TIMBRE = 4;
    static final int AURIC = 5;
    static final int BATERIA = 6;

    final int tipo;
    CharSequence titulo = "";
    CharSequence texto = "";
    /** Icono de app o caratula (si glifo es 0). */
    Drawable icono;
    /** Icono dibujado y animado (GlyphView.*), 0 si se usa icono. */
    int glifo;
    PendingIntent accion;
    /** 0..100, o -1 si no aplica. */
    int nivel = -1;
    /** Linterna encendida, cargando... */
    boolean activo;
    /** Submodo: modo de sonido (0 silencio, 1 vibracion, 2 timbre). */
    int sub;
    int color = 0xFFFFFFFF;

    // Musica
    MediaController control;
    boolean reproduciendo;
    long duracion;
    long posicion;
    float velocidad = 1f;
    long marca;

    Evento(int tipo) {
        this.tipo = tipo;
    }

    long posicionAhora() {
        if (!reproduciendo) return posicion;
        long t = posicion + (long) ((SystemClock.elapsedRealtime() - marca) * velocidad);
        if (duracion > 0) t = Math.min(t, duracion);
        return Math.max(0, t);
    }

    void alternar() {
        try {
            if (control == null) return;
            if (reproduciendo) {
                control.getTransportControls().pause();
            } else {
                control.getTransportControls().play();
            }
        } catch (Throwable ignorado) {
            // el reproductor puede haber cerrado su sesion
        }
    }

    void siguiente() {
        try {
            if (control != null) control.getTransportControls().skipToNext();
        } catch (Throwable ignorado) {
            // idem
        }
    }

    void anterior() {
        try {
            if (control != null) control.getTransportControls().skipToPrevious();
        } catch (Throwable ignorado) {
            // idem
        }
    }
}
