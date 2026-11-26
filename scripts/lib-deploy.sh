#!/usr/bin/env bash
# 只加载仓库内的脚本；环境文件由 Compose 作为数据解析，绝不 source 或 eval。
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_config="$task_root/deploy/.env"
task_release="$task_root/deploy/.release.env"
task_images="$task_root/deploy/.images.env"
for task_command in docker python3; do command -v "$task_command" >/dev/null || { echo "Missing command: $task_command" >&2; exit 1; }; done
docker compose version >/dev/null
[[ -f "$task_config" ]] || { echo "Create deploy/.env first." >&2; exit 1; }

dc() {
  local task_args=(--env-file "$task_config" -f "$task_root/deploy/compose.yml")
  if [[ -f "$task_release" ]]; then task_args+=(--env-file "$task_release"); fi
  if [[ -f "$task_images" ]]; then task_args+=(--env-file "$task_images"); fi
  if grep -qx 'CLONE_PRIVATE_INGRESS=true' "$task_config"; then
    task_args+=(-f "$task_root/deploy/compose.private.yml")
  fi
  docker compose "${task_args[@]}" "$@"
}

immutable_image() { [[ "$1" =~ ^[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}$ ]]; }
immutable_app_image() { immutable_image "$1" || [[ "$1" =~ ^sha256:[a-f0-9]{64}$ ]]; }
lock_deployment() {
  exec 8>"$task_root/deploy/.operation.lock"
  flock -n 8 || { echo 'A release or rollback is already running.' >&2; return 1; }
}
image_value() { sed -n "s/^$1=//p" "$task_images"; }
config_value() {
  # 捕获配置仅在管道内传递，只打印指定的非敏感路径或域名。
  dc config --format json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["services"]["app"]["environment"].get(sys.argv[1],""))' "$1"
}
check_image_schema() {
  local task_image="$1"
  [[ "$(docker image inspect --format '{{index .Config.Labels "cyou.jujube.schema-version"}}' "$task_image")" =~ ^[0-9]+$ ]] || {
    echo "Target image has no schema compatibility contract; do not roll back blindly." >&2; return 1;
  }
  local task_data
  task_data="$(config_value CLONE_DATA_HOST)"
  [[ "$task_data" == /* && "$task_data" != / && "$task_data" != *,* ]] || { echo 'Use an absolute dedicated data directory without commas.' >&2; return 1; }
  # JDBC 只读连接禁止数据写入；目录允许同一应用 UID 管理 WAL/SHM 边车文件。
  docker run --rm --network none --read-only --tmpfs /sqlite-native:rw,exec,nosuid,nodev,size=32m,uid=10001,gid=10001,mode=0700 --mount "type=bind,source=$task_data,target=/data" "$task_image" --check-data-schema /data
}
