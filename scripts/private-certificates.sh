#!/usr/bin/env bash
# 仅用于 SSH 隧道入口。CA 专用于 localhost，leaf 证书自动轮换。
set -euo pipefail
umask 077

prepare_certificate() {
  local task_cert_dir="$1" task_leaf
  [[ "$task_cert_dir" == /* && "$task_cert_dir" != / ]] || return 1
  install -d -m 0700 "$task_cert_dir" "$task_cert_dir/live" "$task_cert_dir/private"
  exec 9>"$task_cert_dir/.lock"
  flock -w 30 9

  if [[ ! -e "$task_cert_dir/private/ca.key" && ! -e "$task_cert_dir/ca.crt" ]]; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -noenc -sha256 -days 1825 \
      -subj '/CN=Clone Me SSH Tunnel Local CA' \
      -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
      -addext 'keyUsage=critical,keyCertSign,cRLSign' \
      -addext 'nameConstraints=critical,permitted;DNS:localhost,permitted;IP:127.0.0.1/255.255.255.255' \
      -keyout "$task_cert_dir/private/ca.key" -out "$task_cert_dir/ca.crt"
  fi
  [[ -s "$task_cert_dir/private/ca.key" && -s "$task_cert_dir/ca.crt" ]] || {
    echo 'The private CA is incomplete. Restore it from secure storage before renewing.' >&2; return 1;
  }
  openssl x509 -checkend 2592000 -noout -in "$task_cert_dir/ca.crt" >/dev/null || {
    echo 'The private CA expires within 30 days. Rotate the trusted CA.' >&2; return 1;
  }
  if openssl x509 -checkend 864000 -noout -in "$task_cert_dir/live/localhost/fullchain.pem" >/dev/null 2>&1; then
    return 0
  fi

  task_leaf="$(mktemp -d "$task_cert_dir/private/leaf.XXXXXXXX")"
  openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -noenc \
    -subj '/CN=localhost' -keyout "$task_leaf/privkey.pem" -out "$task_leaf/request.csr"
  openssl x509 -req -days 30 -sha256 -in "$task_leaf/request.csr" \
    -CA "$task_cert_dir/ca.crt" -CAkey "$task_cert_dir/private/ca.key" \
    -extfile <(printf '%s\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature' \
      'extendedKeyUsage=serverAuth' 'subjectAltName=DNS:localhost,IP:127.0.0.1') \
    -out "$task_leaf/fullchain.pem"
  openssl verify -purpose sslserver -verify_hostname localhost -CAfile "$task_cert_dir/ca.crt" "$task_leaf/fullchain.pem"
  ln -sfn "../private/${task_leaf##*/}" "$task_cert_dir/live/.localhost.next"
  mv -Tf "$task_cert_dir/live/.localhost.next" "$task_cert_dir/live/localhost"
}

case "${1:-}" in
  prepare) prepare_certificate "${2:?Usage: private-certificates.sh prepare /absolute/certificate/directory}" ;;
  renew)
    source "$(dirname -- "${BASH_SOURCE[0]}")/lib-deploy.sh"
    grep -qx 'CLONE_PRIVATE_INGRESS=true' "$task_config"
    task_cert_dir="$(config_value CLONE_CERT_HOST)"
    task_data_dir="$(config_value CLONE_DATA_HOST)"
    prepare_certificate "$task_cert_dir"
    dc exec -T nginx nginx -t
    dc exec -T nginx nginx -s reload
    install -d -m 0755 "$task_data_dir/monitoring"
    task_expiry="$(openssl x509 -enddate -noout -in "$task_cert_dir/live/localhost/fullchain.pem")"
    task_pending="$(mktemp "$task_data_dir/monitoring/.tls-expiry.XXXXXXXX")"
    trap 'rm -f -- "$task_pending"' EXIT
    date -d "${task_expiry#notAfter=}" +%s > "$task_pending"
    chmod 0644 "$task_pending"
    mv -f -- "$task_pending" "$task_data_dir/monitoring/tls-expiry.epoch"
    ;;
  *) echo 'Usage: private-certificates.sh prepare /certificate/directory | renew' >&2; exit 1 ;;
esac
