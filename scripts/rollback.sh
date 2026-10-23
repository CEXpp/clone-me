#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib-deploy.sh"
task_previous="$task_root/deploy/.previous.env"
[[ -f "$task_previous" ]] || { echo 'No previous release is recorded.' >&2; exit 1; }
task_image="$(sed -n 's/^APP_IMAGE=//p' "$task_previous")"
immutable_image "$task_image" || { echo 'Invalid previous image reference.' >&2; exit 1; }
task_current="$task_release"
dc stop app
task_release="$task_previous"
if ! check_image_schema "$task_image"; then
  echo 'Rollback blocked by schema incompatibility. Restore a compatible backup into a new data directory.' >&2
  exit 1
fi
cp -- "$task_previous" "$task_current"
task_release="$task_current"
dc up -d --wait --wait-timeout 150 app nginx
