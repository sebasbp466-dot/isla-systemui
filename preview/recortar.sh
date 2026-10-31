#!/usr/bin/env bash
# Deja solo la parte de arriba de la pantalla (donde esta la isla) para que pese poco.
set +e
OUT=preview/out
mkdir -p "$OUT/png" "$OUT/video"
for f in "$OUT"/png/*.png; do
  [ -f "$f" ] || continue
  ffmpeg -y -loglevel error -i "$f" -vf "crop=iw:ih*0.31:0:0" "$f.tmp.png" && mv "$f.tmp.png" "$f"
done
for f in "$OUT"/video/*.mp4; do
  [ -f "$f" ] || continue
  ffmpeg -y -loglevel error -i "$f" -vf "crop=iw:ih*0.31:0:0" -c:v libx264 -preset veryfast -crf 24 -pix_fmt yuv420p "$f.tmp.mp4" && mv "$f.tmp.mp4" "$f"
done
du -sh "$OUT" 2>/dev/null
