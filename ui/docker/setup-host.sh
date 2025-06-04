#! /bin/sh
cd /root || exit
MY_ENV=$(jq -n env | jq '{KVASIR_HOST} | with_entries(select(.value | . !=null))')
echo $MY_ENV > /app/_ui/_cfg/config.json

exec "$@"
