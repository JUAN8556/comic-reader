#!/bin/zsh
# Encadena «make migrar» hasta que no quede nada pendiente.
# Para si una tanda falla tres veces seguidas (Drive caído, cuota diaria, disco) o al llegar a MAX_TANDAS.
# Uso: nohup zsh tools/migracion/maraton.sh > /dev/null 2>&1 &   (o make mig-maraton)
set -u
cd "$(dirname "$0")/../.." || exit 1
DATA="${COMICS_DATA:-$HOME/comic-reader-data}"
LOG="$DATA/logs/maraton-$(date +%Y%m%d).log"
MAX="${MAX_TANDAS:-30}"
ESPERA_FALLO="${ESPERA_FALLO:-900}"   # segundos tras una PARADA antes de reintentar
mkdir -p "$DATA/logs"
tmp="$(mktemp)"
fallos=0
n=0
echo "== MARATÓN $(date '+%F %T'): hasta $MAX tandas, reintento tras parada a los $ESPERA_FALLO s ==" | tee -a "$LOG"
while (( n < MAX )); do
  n=$((n + 1))
  echo "== $(date '+%F %T') lanzo la tanda $n de como mucho $MAX ==" | tee -a "$LOG"
  make migrar 2>&1 | tee -a "$LOG" > "$tmp"
  rc=${pipestatus[1]}
  if grep -q "No queda nada" "$tmp"; then
    echo "== MARATÓN TERMINADO $(date '+%F %T'): no queda nada que subir ==" | tee -a "$LOG"
    break
  fi
  if grep -qE "PARADA|Traceback|No hay sitio|sin red|no responde" "$tmp" || (( rc != 0 && rc != 1 )); then
    fallos=$((fallos + 1))
    echo "== $(date '+%F %T') la tanda se detuvo (rc=$rc, fallo $fallos de 3); espero $ESPERA_FALLO s ==" | tee -a "$LOG"
    if (( fallos >= 3 )); then
      echo "== MARATÓN DETENIDO $(date '+%F %T'): tres paradas seguidas; revisar el registro ==" | tee -a "$LOG"
      break
    fi
    sleep "$ESPERA_FALLO"
    continue
  fi
  fallos=0
  sleep 30
done
rm -f "$tmp"
make mig-estado --no-print-directory 2>&1 | tee -a "$LOG"
