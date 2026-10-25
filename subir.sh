#!/data/data/com.termux/files/usr/bin/sh
# Todo en uno: sube el codigo, compila en GitHub (gh run watch), baja el APK,
# lo instala, da permisos y reinicia SystemUI.
# Uso (cada vez que cambies algo):   sh subir.sh
set -e
cd "$(dirname "$0")"

PKG=com.lancelot.island
REPO=isla-systemui
APK_DIR="$HOME/isla_apk"
TMP_APK=/data/local/tmp/isla.apk

paso() { printf '\n== %s\n' "$1"; }

command -v gh >/dev/null 2>&1 || { echo "Falta gh: pkg install gh"; exit 1; }
command -v su >/dev/null 2>&1 || { echo "Falta root (su)"; exit 1; }

paso "1/6 Subiendo a GitHub"
[ -d .git ] || git init -b main
git add -A
if ! git diff --cached --quiet; then
  git commit -q -m "Isla SystemUI $(date +%F_%H:%M:%S)"
fi
if git remote get-url origin >/dev/null 2>&1; then
  git push -u origin main
else
  gh repo create "$REPO" --public --source=. --push
fi

paso "2/6 Compilando en GitHub Actions"
SHA=$(git rev-parse HEAD)
RUN_ID=""
for i in $(seq 1 30); do
  RUN_ID=$(gh run list --commit "$SHA" --limit 1 --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true)
  [ -n "$RUN_ID" ] && break
  sleep 2
done
[ -n "$RUN_ID" ] || { echo "No aparecio la compilacion. Revisa: gh run list"; exit 1; }
if ! gh run watch "$RUN_ID" --exit-status; then
  echo ""
  echo "La compilacion fallo. Ultimas lineas del error:"
  gh run view "$RUN_ID" --log-failed | tail -n 40
  exit 1
fi

paso "3/6 Descargando el APK"
NUM=$(gh run view "$RUN_ID" --json number --jq .number)
mkdir -p "$APK_DIR"
rm -f "$APK_DIR"/*.apk
gh release download "build-$NUM" --pattern '*.apk' --clobber -D "$APK_DIR"
ls -l "$APK_DIR"

paso "4/6 Instalando"
su -c "cp '$APK_DIR/IslaSystemUI.apk' $TMP_APK && chmod 644 $TMP_APK"
SALIDA=$(su -c "pm install -r $TMP_APK" 2>&1 || true)
case "$SALIDA" in
  *Success*)
    echo "Actualizada encima, se mantiene la activacion en LSPosed."
    ;;
  *)
    echo "No se pudo actualizar encima (firma distinta). Reinstalando de cero..."
    su -c "pm uninstall $PKG" >/dev/null 2>&1 || true
    su -c "pm install $TMP_APK"
    echo ">> Esta vez hay que activar el modulo en LSPosed (ambito: Interfaz del sistema)."
    ;;
esac
su -c "rm -f $TMP_APK" || true

paso "5/6 Permisos y reinicio de SystemUI"
su -c "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" >/dev/null 2>&1 || true
su -c "pm grant $PKG android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1 || true
su -c "pkill -f com.android.systemui" || true
sleep 8

paso "6/6 Comprobando el hook"
su -c "grep -h -E '\[IslaSystemUI\] (hooks=|eventos iniciados|ventana anadida)' /data/adb/lspd/log/modules_*.log | tail -n 4" || true
echo "(Debe salir 'hooks=1' y 'eventos iniciados'. Si no es reciente, activa el modulo en LSPosed y reinicia SystemUI)"
su -c "am start -n $PKG/.MainActivity" >/dev/null 2>&1 || true
echo ""
echo "Listo. Se abrio la app de ajustes."
