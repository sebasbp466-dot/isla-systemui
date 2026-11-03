#!/usr/bin/env bash
# Vista previa de la isla en un emulador (lo lanza .github/workflows/preview.yml).
# Dispara los mismos eventos de prueba que la app (am broadcast ... TEST), toca y desliza la isla
# con "input" y guarda capturas (png) y videos (mp4) en preview/out.
set +e
PKG=com.lancelot.island
OUT=preview/out
mkdir -p "$OUT/png" "$OUT/video"
exec > >(tee "$OUT/escenario.log") 2>&1
echo "== escenario $(date)"

dormir() { sleep "$1"; }
ev()     { adb shell "am broadcast -a com.lancelot.island.TEST --ei tipo $1 --ei nivel ${2:-72}" >/dev/null 2>&1; }
foto()   { adb exec-out screencap -p > "$OUT/png/$1.png" 2>/dev/null; echo "foto $1"; }
tocar()  { adb shell input tap "$1" "$2"; }
desliza(){ adb shell input swipe "$1" "$2" "$3" "$4" "${5:-150}"; }
estilo() { adb shell settings put secure isla_config "st=$1;en=1;xv=1;xp=1;pm=${2:-0}"; dormir 1; }
rec_ini() {
  adb shell "screenrecord --size 720x1600 --bit-rate 6000000 --time-limit 170 /sdcard/$1.mp4" &
  RECPID=$!
  dormir 2
}
rec_fin() {
  adb shell pkill -2 screenrecord
  wait $RECPID 2>/dev/null
  dormir 2
  adb pull "/sdcard/$1.mp4" "$OUT/video/$1.mp4" >/dev/null 2>&1
}

# ---------------------------------------------------------------- preparacion
adb wait-for-device
for i in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break
  sleep 2
done
echo "emulador listo: $(adb shell getprop ro.product.model | tr -d '\r') $(adb shell wm size | tr -d '\r')"
adb shell settings put global hidden_api_policy 0
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.narrow
dormir 6
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell am force-stop $PKG
adb shell settings put secure isla_config "st=3;en=1;xv=1;xp=1"
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!
adb shell am start -n $PKG/.PreviewActivity
dormir 5

# Geometria real de la isla en este emulador (se escribe en el registro al crear la ventana)
ev 1; dormir 2.5
GEO=$(adb logcat -d -s IslaSystemUI 2>/dev/null | grep "anadida" | tail -1)
echo "GEO: $GEO"
num() { echo "$GEO" | sed -n "s/.*$1=\([0-9]*\).*/\1/p"; }
CX=$(num pillCx);   CX=${CX:-540}
Y=$(num y);         Y=${Y:-40}
H=$(num h);         H=${H:-91}
CT=$(num cardTop);  CT=${CT:-160}
SW=$(num scrW);     SW=${SW:-1080}
PY=$((Y + H / 2))
CY=$((CT + 150))
echo "pildora=($CX,$PY) tarjeta y=$CY ancho=$SW"
dormir 3

# ---------------------------------------------------------------- Xiaomi (estilo 3)
estilo 3
rec_ini xiaomi

# carga: aparicion y desaparicion del banner
ev 1; dormir 0.35; foto x01_carga_a; dormir 0.5; foto x01_carga_b; dormir 3.6; foto x01_carga_fuera

# linterna (elemento fijo) con su tarjeta
ev 2; dormir 1.6; foto x02_linterna_pildora
tocar $CX $PY; dormir 1.1; foto x02_linterna_tarjeta
desliza $CX $((CT + 120)) $CX $((CT - 20)) 160; dormir 0.2; foto x02_linterna_colapso_a; dormir 1.3; foto x02_linterna_despues
ev 3; dormir 1.5

# musica: pildora, tarjeta, arrastre lento, colapso hacia arriba (el caso del salto al reloj)
ev 4; dormir 2.2; foto x03_musica_pildora
tocar $CX $PY; dormir 0.25; foto x03_musica_abriendo; dormir 0.9; foto x03_musica_tarjeta
adb shell input motionevent DOWN $CX $((CT + 180))
adb shell input motionevent MOVE $CX $((CT + 150))
adb shell input motionevent MOVE $CX $((CT + 110))
dormir 0.5; foto x03_musica_arrastre
adb shell input motionevent UP $CX $((CT + 110))
dormir 0.12; foto x03_musica_soltada_a; dormir 0.15; foto x03_musica_soltada_b; dormir 1.6; foto x03_musica_recuperada
# ahora si, deslizar arriba con decision
tocar $CX $PY; dormir 1.4
desliza $CX $((CT + 200)) $CX $((CT - 30)) 150
dormir 0.12; foto x04_colapso_0; dormir 0.12; foto x04_colapso_1; dormir 0.15; foto x04_colapso_2; dormir 1.6; foto x04_colapso_final
# siguiente cancion (deslizar a un lado en la tarjeta)
tocar $CX $PY; dormir 1.3
desliza $((CX + 300)) $CY $((CX - 300)) $CY 130
dormir 0.3; foto x05_musica_lado; dormir 1.4
ev 9; dormir 1.6

# notificaciones: expandida, minimizada y mensajes seguidos
ev 21; dormir 0.4; foto x06_notif_a; dormir 0.9; foto x06_notif_b; dormir 4.5
ev 20; dormir 1.2; foto x07_notif_min; dormir 4
ev 30; dormir 1.0; foto x08_notif_grupo; dormir 5

# llamada
ev 10; dormir 1.6; foto x09_llamada_entrante
ev 11; dormir 1.6; foto x09_llamada_curso
ev 12; dormir 1.6

# cronometro, descarga, volumen, camara
ev 27; dormir 1.6; foto x10_crono
tocar $CX $PY; dormir 1.2; foto x10_crono_tarjeta
ev 28; dormir 1.6
ev 13 35; dormir 1.4; foto x11_descarga; ev 13 90; dormir 1.2; foto x11_descarga_b; dormir 3
ev 19 60; dormir 1.0; foto x12_volumen; dormir 2.5
ev 14; dormir 1.2; foto x13_camara; dormir 3.5

# ajustes rapidos y eventos nuevos
ev 16; dormir 1.0; foto x14_avion; dormir 3.2
ev 31; dormir 1.0; foto x15_hotspot; dormir 3.2
ev 33; dormir 1.0; foto x16_vpn; dormir 3.2
ev 35 82; dormir 1.0; foto x17_bt_bateria; dormir 3.2
ev 6;  dormir 1.0; foto x18_auriculares; dormir 3.2
ev 7;  dormir 1.0; foto x19_bateria_baja; dormir 3.2
ev 26; dormir 1.0; foto x20_carga_rapida; dormir 3.5
rec_fin xiaomi

# ---------------------------------------------------------------- iPhone (estilo 4)
estilo 4
rec_ini iphone
ev 1; dormir 0.4; foto i01_carga_a; dormir 0.6; foto i01_carga_b; dormir 3.2
ev 4; dormir 2.2; foto i02_musica_pildora
tocar $CX $PY; dormir 0.3; foto i02_musica_abriendo; dormir 0.9; foto i02_musica_tarjeta
desliza $CX $((CT + 200)) $CX $((CT - 30)) 150
dormir 0.12; foto i03_colapso_0; dormir 0.15; foto i03_colapso_1; dormir 1.6; foto i03_colapso_final
ev 9; dormir 1.6
ev 21; dormir 0.5; foto i04_notif_a; dormir 1.0; foto i04_notif_b; dormir 4.5
ev 10; dormir 1.6; foto i05_llamada; ev 12; dormir 1.6
ev 2; dormir 1.6; foto i06_linterna; ev 3; dormir 1.5
rec_fin iphone

# ---------------------------------------------------------------- Liquid Glass (estilo 5)
estilo 5
rec_ini liquid
ev 1; dormir 0.4; foto l01_carga_a; dormir 0.6; foto l01_carga_b; dormir 3.2
ev 4; dormir 2.2; foto l02_musica_pildora
tocar $CX $PY; dormir 0.35; foto l02_musica_abriendo; dormir 1.0; foto l02_musica_tarjeta
# boton de cristal (play) con el dedo apoyado: se ve la lente y el brillo
PLX=$((SW / 2)); PLY=$((CT + 196 * SW / 720))
adb shell input motionevent DOWN $PLX $PLY; dormir 0.5; foto l03_boton_pulsado
adb shell input motionevent UP $PLX $PLY; dormir 0.8; foto l03_boton_suelto
# colapso hacia arriba (el caso del salto hacia el reloj)
desliza $CX $((CT + 200)) $CX $((CT - 30)) 150
dormir 0.12; foto l04_colapso_0; dormir 0.15; foto l04_colapso_1; dormir 0.2; foto l04_colapso_2; dormir 1.6; foto l04_colapso_final
ev 9; dormir 1.6
ev 21; dormir 0.5; foto l05_notif_a; dormir 1.0; foto l05_notif_b; dormir 4.5
ev 10; dormir 1.6; foto l06_llamada; ev 12; dormir 1.6
ev 2; dormir 1.6; foto l07_linterna; tocar $CX $PY; dormir 1.2; foto l07_linterna_tarjeta; ev 3; dormir 1.5
rec_fin liquid

# ---------------------------------------------------------------- cierre
kill $LOGPID 2>/dev/null
adb logcat -d -s IslaSystemUI > "$OUT/logcat_isla.txt" 2>&1
echo "== fin $(date)"
