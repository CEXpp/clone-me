#!/usr/bin/env bash
# 在新服务器路径初始化独立的 Clone Me 实例。更新版本使用 deploy.sh。
set -euo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo 'Run the initial installation as root.' >&2; exit 1; }
task_source="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_revision="${1:?Specify the source commit SHA}"
task_release_id="${2:?Specify the release identifier}"
[[ "$task_revision" =~ ^[a-f0-9]{40}$ && "$task_release_id" =~ ^[a-zA-Z0-9_-]+$ ]]
task_base=/srv/clone-me
task_runtime="$task_base/runtime"
task_archive="$task_base/releases/$task_release_id"
[[ ! -e "$task_base" ]] || { echo 'Installation directory already exists. Inspect it before proceeding.' >&2; exit 1; }
[[ -z "$(docker ps -aq --filter label=com.docker.compose.project=clone-me)" ]] || {
  echo 'A Compose project with this name already exists.' >&2; exit 1;
}

# 先拉取经过版本锁定的依赖并构建。网络失败时不初始化服务器运行目录。
[[ -f "$task_source/deploy/.images.env" ]] || bash "$task_source/scripts/freeze-images.sh"
task_tag="clone-me-local:$task_release_id"
CLONE_BUILD_REVISION="$task_revision" bash "$task_source/scripts/build-image.sh" "$task_tag"
task_image="$(docker image inspect --format '{{.Id}}' "$task_tag")"
install -d -m 0750 "$task_base" "$task_runtime" "$task_archive"
cp -a -- "$task_source/." "$task_archive/"
cp -a -- "$task_archive/deploy" "$task_archive/scripts" "$task_runtime/"
install -d -m 0750 -o 10001 -g 10001 "$task_base/data"
install -d -m 0755 "$task_base/acme"
install -d -m 0700 "$task_base/credentials"

cd -- "$task_runtime"
sed -e 's/^CLONE_DOMAIN=.*/CLONE_DOMAIN=localhost/' \
  -e 's/^CLONE_PRIVATE_INGRESS=.*/CLONE_PRIVATE_INGRESS=true/' \
  -e 's/^TURNSTILE_HOSTNAME=.*/TURNSTILE_HOSTNAME=localhost/' \
  -e "s|^CLONE_MASTER_KEY=.*|CLONE_MASTER_KEY=$(openssl rand -base64 32 | tr -d '\n')|" \
  deploy/.env.example > deploy/.env
chmod 0600 deploy/.env
bash scripts/private-certificates.sh prepare "$task_base/certificates"
source scripts/lib-deploy.sh

export CLONE_ADMIN_PASSWORD="$(openssl rand -hex 24)"
task_enrollment="$task_base/credentials/admin-enrollment.txt"
printf 'Username: owner\nInitial password: %s\n' "$CLONE_ADMIN_PASSWORD" > "$task_enrollment"
if ! APP_IMAGE="$task_image" dc run --rm --no-deps -T -e CLONE_ADMIN_USERNAME=owner \
  -e CLONE_ADMIN_PASSWORD app --initialize-admin >> "$task_enrollment" 2>&1; then
  echo "Administrator initialization failed. Inspect $task_enrollment privately." >&2
  exit 1
fi
unset CLONE_ADMIN_PASSWORD

bash scripts/deploy.sh "$task_image"
install -m 0644 deploy/systemd/clone-me-private-certificates.service deploy/systemd/clone-me-private-certificates.timer /etc/systemd/system/
systemctl daemon-reload
systemctl start clone-me-private-certificates.service
systemctl enable --now clone-me-private-certificates.timer
sha256sum "$task_archive/backend/target/clone-me-0.1.0-SNAPSHOT.jar"
openssl x509 -in "$task_base/certificates/ca.crt" -noout -sha256 -fingerprint
echo "Administrator enrollment is protected in $task_enrollment."
