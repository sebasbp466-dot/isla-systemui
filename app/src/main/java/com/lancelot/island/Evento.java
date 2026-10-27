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
    static final int LLAMADA = 7;
    static final int PROGRESO = 8;
    static final int PRIVACIDAD = 9;
    static final int AJUSTE = 10;
    static final int VOLUMEN = 11;
    static final int PORTAPAPELES = 12;
    static final int CRONO = 13;
    /** Capsula vacia de la isla permanente (no se expande). */
    static final int IDLE = 14;

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
    /** Linterna encendida, cargando, sonando... */
    boolean activo;
    /**
     * Submodo. Timbre: 0 silencio, 1 vibracion, 2 timbre.
     * Llamada: 1 entrante, 2 en curso, 3 terminada o perdida.
     */
    int sub;
    int color = 0xFFFFFFFF;
    /** Identifica al evento para refrescarlo (descargas, volumen). */
    String clave;
    /** Cuanto tiempo se queda expandido; 0 usa el valor por defecto. */
    int duracionMs;

    /** Paquete de la app que origino el evento (notificaciones). */
    String pkg;
    /** Nombre de la app (se ve como subtitulo pequeño en la notificacion expandida). */
    CharSequence app;
    /** Foto grande de la notificacion (miniatura a la derecha). */
    Drawable imagen;
    /** El cronometro cuenta hacia atras (temporizador). */
    boolean cuentaAtras;
    /** Mensajes seguidos de la misma app agrupados en esta isla. */
    int agrupadas = 1;
    /** Accion de respuesta rapida de la notificacion (con RemoteInput). */
    PendingIntent respPi;
    android.app.RemoteInput[] respRi;
    /** Cuantas notificaciones pendientes hay (para el numero de la cápsula minimizada). */
    int contador;
    /** Llega minimizada: solo la capsula compacta, se expande al tocarla. */
    boolean minimizada;
    /** Primera accion nativa de la notificacion (sin respuesta de texto). */
    CharSequence nativaTxt;
    PendingIntent nativaPi;
    /** Botones bajo el texto (llamadas y panel de acciones). */
    String[] botonTxt;
    int[] botonColor;
    Runnable[] botonRun;

    // Musica
    MediaController control;
    boolean reproduciendo;
    long duracion;
    long posicion;
    float velocidad = 1f;
    long marca;

    // Llamada en curso
    long base;

    Evento(int tipo) {
        this.tipo = tipo;
    }

    Evento copiar() {
        Evento o = new Evento(tipo);
        o.titulo = titulo;
        o.texto = texto;
        o.icono = icono;
        o.glifo = glifo;
        o.accion = accion;
        o.nivel = nivel;
        o.activo = activo;
        o.sub = sub;
        o.color = color;
        o.clave = clave;
        o.duracionMs = duracionMs;
        o.pkg = pkg;
        o.minimizada = minimizada;
        o.nativaTxt = nativaTxt;
        o.nativaPi = nativaPi;
        o.base = base;
        o.contador = contador;
        o.app = app;
        o.imagen = imagen;
        o.cuentaAtras = cuentaAtras;
        o.agrupadas = agrupadas;
        o.respPi = respPi;
        o.respRi = respRi;
        return o;
    }

    long posicionAhora() {
        if (!reproduciendo) return posicion;
        long t = posicion + (long) ((SystemClock.elapsedRealtime() - marca) * velocidad);
        if (duracion > 0) t = Math.min(t, duracion);
        return Math.max(0, t);
    }

    boolean mismaClave(Evento o) {
        if (clave == null) return o.clave == null;
        return clave.equals(o.clave);
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

    void buscar(long ms) {
        try {
            if (control != null) control.getTransportControls().seekTo(ms);
        } catch (Throwable ignorado) {
            // idem
        }
    }
}
