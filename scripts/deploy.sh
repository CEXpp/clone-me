#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib-deploy.sh"
task_image="${1:?Usage: deploy.sh registry/repository@sha256:digest}"
immutable_image "$task_image" || { echo 'Use an immutable application image digest.' >&2; exit 1; }
[[ -f "$task_images" ]] || { echo 'Install the release image lock file first.' >&2; exit 1; }
for task_name in NGINX_IMAGE CERTBOT_IMAGE; do immutable_image "$(image_value "$task_name")" || { echo 'Invalid release image lock.' >&2; exit 1; }; done
umask 077
task_candidate="$task_root/deploy/.release.env.next"
task_previous="$task_root/deploy/.previous.env"
printf 'APP_IMAGE=%s\n' "$task_image" > "$task_candidate"
docker pull "$task_image"
task_old_release="$task_release"
task_release="$task_candidate"
dc config --quiet
check_image_schema "$task_image"
task_release="$task_old_release"
if [[ -f "$task_release" ]]; then
  # 旧镜像完成升级前备份；不使用新程序先迁移再备份。
  task_old_image="$(sed -n 's/^APP_IMAGE=//p' "$task_release")"
  [[ "$(docker image inspect --format '{{index .Config.Labels "cyou.jujube.schema-version"}}' "$task_old_image")" =~ ^[0-9]+$ ]] || {
    echo 'Legacy release needs a separately verified offline backup and migration procedure.' >&2; exit 1;
  }
  dc stop app
  task_data="$(config_value CLONE_DATA_HOST)"
  if ! docker run --rm --network none --read-only --env-file "$task_config" --tmpfs /tmp:size=64m,mode=1777 --mount "type=bind,source=$task_data,target=/data" "$task_old_image" --backup-data; then
    echo 'Pre-upgrade backup failed; restarting the unchanged previous application.' >&2
    dc up -d --wait --wait-timeout 150 app
    exit 1
  fi
  cp -- "$task_release" "$task_previous"
fi
mv -- "$task_candidate" "$task_old_release"
task_release="$task_old_release"
if ! dc up -d --wait --wait-timeout 150 app nginx; then
  echo 'Deployment failed. Checking whether the previous image can use this database.' >&2
  dc stop app
  if [[ -f "$task_previous" ]]; then
    task_failed_release="$task_release"
    task_release="$task_previous"
    task_old_image="$(sed -n 's/^APP_IMAGE=//p' "$task_previous")"
    if check_image_schema "$task_old_image"; then
      cp -- "$task_previous" "$task_failed_release"
      task_release="$task_failed_release"
      dc up -d --wait --wait-timeout 150 app nginx
    else
      echo 'Schema is incompatible. Keep the application stopped and restore the pre-upgrade backup to a new directory.' >&2
    fi
  fi
  exit 1
fi
dc exec -T app java -Xmx64m -jar /app/app.jar --healthcheck
echo "Deployment healthy: $task_image"
