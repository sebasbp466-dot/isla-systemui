#!/data/data/com.termux/files/usr/bin/sh
# Graba la pantalla con root (sin instalar nada) para comparar la isla con el video.
# Uso:  sh grabar.sh [segundos]     (por defecto 40, maximo 180)
SEG="${1:-40}"
[ "$SEG" -gt 180 ] && SEG=180
OUT=/sdcard/Download/isla_grabacion.mp4
command -v su >/dev/null 2>&1 || { echo "Falta root (su)"; exit 1; }
echo "Grabando $SEG s en 5 segundos... sal de Termux y prueba la isla (app > Probar)."
sleep 5
su -c "rm -f $OUT; screenrecord --time-limit $SEG --bit-rate 8000000 --size 720x1600 $OUT"
echo "Listo: $OUT"
ls -la "$OUT" 2>/dev/null || su -c "ls -la $OUT"
