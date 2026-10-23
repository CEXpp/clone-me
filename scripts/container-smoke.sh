#!/usr/bin/env bash
# 仅在隔离 Docker 网络中使用一次性资料、管理员与自签名测试证书，不访问真实供应商。
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_image="${1:?Specify the locally built application image}"
task_temp="$(mktemp -d)"
task_network="clone-test-$$"
task_app="clone-app-test-$$"
task_proxy="clone-proxy-test-$$"
task_nginx="$(sed -n 's/^NGINX_IMAGE=//p' "$task_root/deploy/.images.env")"
task_python="$(sed -n 's/^CERTBOT_IMAGE=//p' "$task_root/deploy/.images.env")"
cleanup() {
  docker rm -f "$task_proxy" "$task_app" >/dev/null 2>&1 || true
  docker network rm "$task_network" >/dev/null 2>&1 || true
  # mktemp 生成的目录经过绝对路径和固定前缀检查后才清理。
  if [[ "$task_temp" == /tmp/tmp.* || "$task_temp" == "${RUNNER_TEMP:-/nonexistent}/"* ]]; then sudo rm -rf -- "$task_temp"; fi
}
trap cleanup EXIT
mkdir -p "$task_temp/data" "$task_temp/certs/live/clone.test"
chmod 755 "$task_temp"
sudo chown 10001:10001 "$task_temp/data"
task_master="$(python3 -c 'import secrets,base64; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
if ! docker run --rm --network none --read-only --tmpfs /tmp:mode=1777,size=64m --mount "type=bind,source=$task_temp/data,target=/data" \
  -e "CLONE_MASTER_KEY=$task_master" -e CLONE_ADMIN_USERNAME=fixture_owner -e CLONE_ADMIN_PASSWORD=container-fixture-password-2026 \
  -e CLONE_CHAT_ENABLED=false "$task_image" --initialize-admin > "$task_temp/fixture-bootstrap.log"; then
  # 仅输出启动错误，不打印合成测试身份的注册信息和恢复码。
  grep -E 'Exception|Caused by:|ERROR|APPLICATION FAILED|Description:|Action:' "$task_temp/fixture-bootstrap.log" || true
  exit 1
fi
docker network create --subnet 172.30.23.0/29 "$task_network" >/dev/null
docker run -d --name "$task_app" --network "$task_network" --network-alias app --ip 172.30.23.3 \
  --read-only --cap-drop ALL --security-opt no-new-privileges --memory 1g --cpus 1.5 --tmpfs /tmp:mode=1777,size=64m \
  --mount "type=bind,source=$task_temp/data,target=/data" -e "CLONE_MASTER_KEY=$task_master" -e CLONE_CHAT_ENABLED=false "$task_image" >/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=clone.test \
  -addext 'subjectAltName=DNS:clone.test,IP:127.0.0.1,IP:172.30.23.2' \
  -keyout "$task_temp/certs/live/clone.test/privkey.pem" -out "$task_temp/certs/live/clone.test/fullchain.pem" >/dev/null 2>&1
python3 - "$task_root" "$task_temp" <<'PY'
from pathlib import Path
import sys
root,temp=map(Path,sys.argv[1:])
text=(root/'deploy/nginx/https.conf.template').read_text().replace('${CLONE_DOMAIN}','clone.test')
(temp/'nginx.conf').write_text(text)
PY
for task_attempt in $(seq 1 50); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' "$task_app")" == healthy ]]; then break; fi
  sleep 2
done
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$task_app")" == healthy ]] || { docker logs "$task_app"; exit 1; }
docker run -d --name "$task_proxy" --network "$task_network" --ip 172.30.23.2 \
  -p 127.0.0.1:18443:443 --mount "type=bind,source=$task_temp/nginx.conf,target=/etc/nginx/conf.d/default.conf,readonly" \
  --mount "type=bind,source=$task_temp/certs,target=/etc/letsencrypt,readonly" "$task_nginx" >/dev/null
for task_attempt in $(seq 1 20); do
  if curl --silent --fail --cacert "$task_temp/certs/live/clone.test/fullchain.pem" https://127.0.0.1:18443/api/health/ready >/dev/null; then break; fi
  sleep 1
done
curl --silent --fail --cacert "$task_temp/certs/live/clone.test/fullchain.pem" https://127.0.0.1:18443/api/health/ready
curl --silent --fail --cacert "$task_temp/certs/live/clone.test/fullchain.pem" https://127.0.0.1:18443/ | grep -q 'id="app"'
[[ "$(docker inspect --format '{{.Config.User}}' "$task_app")" == '10001:10001' ]]
[[ -z "$(docker port "$task_app")" ]]
python3 - "$task_temp" <<'PY'
from pathlib import Path
import sys,ssl,json,urllib.request,http.cookiejar,sqlite3,hashlib
temp=Path(sys.argv[1])
jar=http.cookiejar.CookieJar()
opener=urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(temp/'certs/live/clone.test/fullchain.pem'))),urllib.request.HTTPCookieProcessor(jar))
csrf=json.load(opener.open('https://127.0.0.1:18443/api/auth/csrf'))
for n in range(3):
    request=urllib.request.Request('https://127.0.0.1:18443/api/public/session',data=b'{}',headers={'Content-Type':'application/json',csrf['headerName']:csrf['token'],'X-Forwarded-For':f'203.0.113.{n+1}'})
    with opener.open(request) as response: assert response.status==200
assert any(c.name=='CLONE_VISITOR' and c.secure for c in jar)
with sqlite3.connect((temp/'data/app.db').as_uri()+'?mode=ro',uri=True) as db:
    keys={r[0].rsplit(':',1)[0] for r in db.execute("SELECT key FROM rate_limits WHERE key LIKE 'session-create:%'")}
assert len(keys)==1,keys
assert all(hashlib.sha256(b'172.30.23.2').hexdigest() not in key for key in keys), 'Proxy address was used instead of client address'
print('HTTPS, Secure cookies and spoofed forwarded-header isolation verified')
PY
# 第二个真实网络地址使用已有 Certbot 镜像里的 Python 发请求，验证不共用同一个 IP 桶。
docker run --rm --network "$task_network" --ip 172.30.23.4 --entrypoint python \
  --mount "type=bind,source=$task_temp/certs/live/clone.test/fullchain.pem,target=/tmp/ca.pem,readonly" "$task_python" -c '
import ssl,json,urllib.request,http.cookiejar
jar=http.cookiejar.CookieJar()
opener=urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile="/tmp/ca.pem")),urllib.request.HTTPCookieProcessor(jar))
base="https://172.30.23.2"
csrf=json.load(opener.open(base+"/api/auth/csrf"))
response=opener.open(urllib.request.Request(base+"/api/public/session",data=b"{}",headers={"Content-Type":"application/json",csrf["headerName"]:csrf["token"]}))
assert response.status==200
'
python3 - "$task_temp" <<'PY'
import sqlite3,sys
from pathlib import Path
file=Path(sys.argv[1])/'data/app.db'
with sqlite3.connect(file.as_uri()+'?mode=ro',uri=True) as db:
    keys={r[0].rsplit(':',1)[0] for r in db.execute("SELECT key FROM rate_limits WHERE key LIKE 'session-create:%'")}
    assert len(keys)==2,keys
print('Distinct client IP rate-limit buckets verified')
PY
docker restart "$task_app" >/dev/null
for task_attempt in $(seq 1 50); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' "$task_app")" == healthy ]]; then break; fi
  sleep 2
done
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$task_app")" == healthy ]]
echo 'Container persistence, health and proxy smoke checks passed.'
