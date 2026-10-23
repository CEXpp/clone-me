#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib-deploy.sh"
task_action="${1:?Usage: certificates.sh issue|renew|check [domain email]}"
case "$task_action" in
  issue)
    task_domain="${2:?Specify the configured domain}"
    task_email="${3:?Specify the certificate email}"
    [[ "$task_domain" =~ ^[a-zA-Z0-9.-]+$ && "$task_email" == *@* ]] || { echo 'Invalid domain or email.' >&2; exit 1; }
    task_configured_domain="$(dc config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["nginx"]["environment"]["CLONE_DOMAIN"])')"
    [[ "$task_domain" == "$task_configured_domain" ]] || { echo 'Domain differs from deploy/.env.' >&2; exit 1; }
    CLONE_TLS_MODE=http dc up -d --no-deps nginx
    dc run --rm --no-deps certbot certonly --non-interactive --agree-tos --webroot -w /var/www/certbot --email "$task_email" -d "$task_domain"
    CLONE_TLS_MODE=https dc up -d --no-deps --force-recreate nginx
    ;;
  renew)
    dc run --rm --no-deps certbot renew --non-interactive
    dc exec -T nginx nginx -t
    dc exec -T nginx nginx -s reload
    ;;
  check) dc run --rm --no-deps certbot renew --dry-run --non-interactive ;;
  *) echo 'Unknown certificate action.' >&2; exit 1 ;;
esac
# 只将公开证书到期时间上报给应用，应用容器不挂载或读取私钥。
task_data="$(config_value CLONE_DATA_HOST)"
task_certificates="$(config_value CLONE_CERT_HOST)"
task_domain="$(dc config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["nginx"]["environment"]["CLONE_DOMAIN"])')"
command -v openssl >/dev/null
task_expiry="$(openssl x509 -in "$task_certificates/live/$task_domain/fullchain.pem" -noout -enddate)"
mkdir -p -- "$task_data/monitoring"
date -d "${task_expiry#notAfter=}" +%s > "$task_data/monitoring/tls-expiry.epoch"
chmod 644 "$task_data/monitoring/tls-expiry.epoch"
