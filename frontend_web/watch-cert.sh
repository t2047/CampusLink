#!/bin/sh
# Certbot 与 Nginx 分属不同容器。证书文件更新后定期平滑 reload，避免续期后仍使用旧证书。
set -eu

CERT_FILE="/etc/letsencrypt/live/${CERT_DOMAIN}/fullchain.pem"

(
    previous=""
    if [ -f "$CERT_FILE" ]; then
        previous="$(sha256sum "$CERT_FILE" | awk '{print $1}')"
    fi
    while :; do
        sleep 3600
        [ -f "$CERT_FILE" ] || continue
        current="$(sha256sum "$CERT_FILE" | awk '{print $1}')"
        if [ -n "$previous" ] && [ "$current" != "$previous" ]; then
            echo "[campuslink] TLS certificate changed; reloading nginx"
            nginx -s reload
        fi
        previous="$current"
    done
) &
