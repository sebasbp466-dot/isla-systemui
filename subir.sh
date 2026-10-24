#!/data/data/com.termux/files/usr/bin/sh
# Sube el proyecto a GitHub y dispara la compilacion.
set -e
cd "$(dirname "$0")"

[ -d .git ] || git init -b main
git add -A
git commit -m "Isla SystemUI" || true

if command -v gh >/dev/null 2>&1; then
  gh repo create isla-systemui --public --source=. --push || git push -u origin main
  echo "Listo. Mira la compilacion con:  gh run watch"
  echo "Cuando termine, baja el APK con:  gh release download --pattern '*.apk' --clobber"
else
  echo "Falta gh. Instalalo con: pkg install gh"
  echo "O crea el repo a mano en GitHub y haz: git remote add origin <url> && git push -u origin main"
fi
