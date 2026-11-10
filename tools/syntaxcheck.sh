#!/bin/bash
# Revisa solo errores de SINTAXIS (no tenemos la API de Paper aqui, asi que se ignoran los "no existe el paquete/simbolo").
# Uso: tools/syntaxcheck.sh
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
javac -proc:none -Xmaxerrs 5000 -d "$OUT" $(find src/main/java -name '*.java') > "$OUT/log" 2>&1
ERRS=$(grep -E "error:" "$OUT/log" | grep -vE "package [a-zA-Z0-9_.]+ does not exist|cannot find symbol|cannot access|static import only from classes" )
if [ -z "$ERRS" ]; then
  echo "SINTAXIS OK (sin errores de parseo). Errores de simbolos/paquetes externos ignorados: $(grep -c 'error:' "$OUT/log")"
else
  echo "$ERRS" | head -60
fi
rm -rf "$OUT"
