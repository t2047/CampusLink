#!/bin/sh
# 在云服务器仓库根目录执行。只处理 CERT_DOMAIN 对应的自签占位证书。
set -eu

: "${CERT_DOMAIN:?请在服务器 .env 中设置 CERT_DOMAIN}"
: "${CERT_EMAIL:?请在服务器 .env 中设置 CERT_EMAIL}"

case "$CERT_DOMAIN" in
    *[!A-Za-z0-9.-]*|.*|*..*|*.)
        echo "CERT_DOMAIN 格式不合法" >&2
        exit 2
        ;;
esac

COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
CERT_DIR="/etc/letsencrypt/live/$CERT_DOMAIN"

echo "[campuslink] 检查域名解析：$CERT_DOMAIN"
resolved_ips="$(getent ahostsv4 "$CERT_DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u)"
[ -n "$resolved_ips" ] || {
    echo "域名尚未解析到可访问的 IPv4 地址" >&2
    exit 3
}

# 优先使用服务器显式配置，其次读取 EC2 IMDSv2，最后使用公网回显服务。
server_ip="${SERVER_PUBLIC_IP:-}"
if [ -z "$server_ip" ]; then
    imds_token="$(curl --silent --show-error --max-time 2 -X PUT \
        -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
        http://169.254.169.254/latest/api/token 2>/dev/null || true)"
    if [ -n "$imds_token" ]; then
        server_ip="$(curl --silent --show-error --max-time 2 \
            -H "X-aws-ec2-metadata-token: $imds_token" \
            http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || true)"
    fi
fi
if [ -z "$server_ip" ]; then
    server_ip="$(curl --fail --silent --show-error --max-time 5 https://checkip.amazonaws.com 2>/dev/null \
        | tr -d '[:space:]' || true)"
fi
[ -n "$server_ip" ] || {
    echo "无法确定当前服务器公网 IP；请在服务器 .env 设置 SERVER_PUBLIC_IP" >&2
    exit 4
}
echo "$resolved_ips" | grep -Fqx "$server_ip" || {
    echo "DNS 未指向当前服务器：解析结果=$resolved_ips，当前服务器=$server_ip" >&2
    exit 5
}

# 先启动 Web，使 ACME Webroot 可访问。此时可能仍使用自签占位证书。
$COMPOSE up -d web

placeholder="$($COMPOSE exec -T web sh -c "
    if [ -f '$CERT_DIR/.campuslink-self-signed' ]; then
        echo yes
    elif [ -f '$CERT_DIR/fullchain.pem' ]; then
        subject=\$(openssl x509 -in '$CERT_DIR/fullchain.pem' -noout -subject 2>/dev/null || true)
        issuer=\$(openssl x509 -in '$CERT_DIR/fullchain.pem' -noout -issuer 2>/dev/null || true)
        [ \"\${subject#subject=}\" = \"\${issuer#issuer=}\" ] && echo yes || echo no
    else
        echo no
    fi
")"

if [ "$placeholder" = "yes" ]; then
    echo "[campuslink] 删除该域名的自签占位证书"
    $COMPOSE run --rm --entrypoint /bin/sh certbot -c \
        "rm -rf '/etc/letsencrypt/live/$CERT_DOMAIN' '/etc/letsencrypt/archive/$CERT_DOMAIN'; rm -f '/etc/letsencrypt/renewal/$CERT_DOMAIN.conf'"
fi

echo "[campuslink] 申请或复用 Let's Encrypt 证书"
$COMPOSE run --rm --entrypoint certbot certbot certonly \
    --webroot -w /var/www/certbot \
    -d "$CERT_DOMAIN" \
    --email "$CERT_EMAIL" \
    --agree-tos --no-eff-email --keep-until-expiring --non-interactive

$COMPOSE restart web

echo "[campuslink] 验证 HTTPS"
curl --fail --silent --show-error --max-time 20 "https://$CERT_DOMAIN/" >/dev/null
curl --fail --silent --show-error --head --max-time 20 "http://$CERT_DOMAIN/" | grep -Eq "^HTTP/[0-9.]+ 301"
echo "[campuslink] HTTPS 已启用：https://$CERT_DOMAIN/"
