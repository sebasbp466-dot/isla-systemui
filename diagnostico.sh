#!/data/data/com.termux/files/usr/bin/sh
# Prueba la isla sola: lanza eventos con cada estilo, saca capturas de la parte de arriba,
# guarda el log y lo sube a la rama "diag" de tu repositorio para que Claude lo revise.
# Uso:   sh diagnostico.sh
cd "$(dirname "$0")"
URL=$(git remote get-url origin 2>/dev/null)
[ -n "$URL" ] || { echo "No hay repositorio conectado. Ejecuta antes: sh subir.sh"; exit 1; }
command -v su >/dev/null 2>&1 || { echo "Falta root (su)"; exit 1; }
OUT="$HOME/isla_diag"
rm -rf "$OUT"; mkdir -p "$OUT"
command -v convert >/dev/null 2>&1 || pkg install imagemagick -y >/dev/null 2>&1

CFG0=$(su -c "settings get secure isla_config")
echo "Config original: $CFG0"
echo "Desbloquea el telefono y NO lo toques durante ~2 minutos."
su -c "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard" >/dev/null 2>&1
sleep 2

ev() { su -c "am broadcast -a com.lancelot.island.TEST -p com.android.systemui --ei tipo $1" >/dev/null 2>&1; }

foto() {
  su -c "screencap -p /data/local/tmp/isla_x.png && chmod 644 /data/local/tmp/isla_x.png"
  if command -v convert >/dev/null 2>&1; then
    convert /data/local/tmp/isla_x.png -gravity North -crop 100%x24%+0+0 +repage \
      -quality 82 "$OUT/$1.jpg" 2>/dev/null
  else
    cp /data/local/tmp/isla_x.png "$OUT/$1.png"
  fi
  su -c "rm -f /data/local/tmp/isla_x.png"
}

# nombre, tipo, espera antes de la captura, tipo para limpiar (0 = nada)
caso() {
  ev "$2"; sleep "$3"; foto "$ESTILO-$1"
  [ "$4" != "0" ] && { ev "$4"; sleep 1.5; }
  sleep 2.5
}

for ST in 0 1 2; do
  case $ST in 0) ESTILO=hyper;; 1) ESTILO=cristal;; 2) ESTILO=neon;; esac
  NUEVA=$(printf '%s' "$CFG0" | sed "s/st=[0-9]/st=$ST/")
  case "$NUEVA" in *st=*) ;; *) NUEVA="${NUEVA};st=$ST";; esac
  su -c "settings put secure isla_config '$NUEVA'"
  sleep 3
  echo "== Estilo $ESTILO"
  caso carga 1 1.4 0
  caso linterna 2 1.4 3
  caso musica 4 1.4 9
  caso notif 21 1.4 0
  caso notif_minimizada 20 1.2 0
  caso foto 29 1.4 0
  caso mensajes 30 2.2 0
  caso llamada 11 1.4 12
  caso descarga 13 1.4 0
  caso temporizador 27 1.4 28
done

su -c "settings put secure isla_config '$CFG0'"
{
  echo "modelo: $(getprop ro.product.model) / $(getprop ro.build.display.id)"
  echo "android: $(getprop ro.build.version.release)"
  echo "pantalla: $(su -c 'wm size' | tr '\n' ' ') $(su -c 'wm density' | tr '\n' ' ')"
  echo "config: $CFG0"
  echo "--- modulo"
  su -c "cat /data/adb/lspd/log/modules_*.log 2>/dev/null | grep -a 'IslaSystemUI' | tail -n 120"
  echo "--- crashes recientes"
  su -c "logcat -d -b crash -t 80 2>/dev/null"
} > "$OUT/log.txt" 2>&1

cd "$OUT"
git init -q -b diag
git config user.email "diag@isla.local"; git config user.name "diag"
git add -A && git commit -q -m "Diagnostico $(date +%F_%H:%M)"
git remote add origin "$URL"
git push -q -f origin HEAD:refs/heads/diag && echo "Listo: subido a la rama diag. Avisale a Claude."
ls "$OUT" | head -50
