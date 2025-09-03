#!/bin/bash

URL="https://order-order.apps.cluster-4fg5r.4fg5r.sandbox5322.opentlc.com/users"
CONCURRENCIA=250
TOTAL_REQUESTS=25000

echo "Iniciando prueba de carga concurrente contra: $URL"
echo "Lanzando $TOTAL_REQUESTS solicitudes en bloques de $CONCURRENCIA concurrentes..."

seq $TOTAL_REQUESTS | xargs -n1 -P$CONCURRENCIA -I{} \
  sh -c 'status_code=$(curl -s -o /dev/null -w "%{http_code}" -X GET -H "Accept: application/json" "$0"); echo "Solicitud #{}: Código de estado -> $status_code"' "$URL"

echo "Prueba de carga finalizada."