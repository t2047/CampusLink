#!/bin/sh
# nginx 启动前置：443 证书不存在时生成自签占位证书（否则 nginx 因缺证书无法启动）。
# certbot 首次签发真证书后（覆盖同一路径）restart web 即切换 HTTPS。
set -e

CERT_DIR="/etc/letsencrypt/live/${CERT_DOMAIN}"
if [ -f "$CERT_DIR/fullchain.pem" ] && [ -f "$CERT_DIR/privkey.pem" ]; then
    exit 0
fi

echo "[campuslink] certificate missing for ${CERT_DOMAIN}; generating self-signed placeholder"
mkdir -p "$CERT_DIR"
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
    -keyout "$CERT_DIR/privkey.pem" \
    -out "$CERT_DIR/fullchain.pem" \
    -subj "/CN=${CERT_DOMAIN}" >/dev/null 2>&1
touch "$CERT_DIR/.campuslink-self-signed"
