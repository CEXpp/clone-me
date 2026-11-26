#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
command -v docker >/dev/null
umask 077
task_output="$task_root/deploy/.images.env.next"
trap 'rm -f -- "$task_output"' EXIT
declare -a task_names=(RUNTIME_IMAGE NGINX_IMAGE CERTBOT_IMAGE)
declare -a task_tags=(eclipse-temurin:25.0.4.1_1-jre-jammy nginx:1.30.6-alpine certbot/certbot:v5.8.0)
: > "$task_output"
for task_index in 0 1 2; do
  task_tag="${task_tags[$task_index]}"
  docker pull "$task_tag"
  task_digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$task_tag")"
  [[ "$task_digest" =~ ^[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid registry digest.' >&2; exit 1; }
  printf '%s=%s\n' "${task_names[$task_index]}" "$task_digest" >> "$task_output"
done
mv -- "$task_output" "$task_root/deploy/.images.env"
echo 'Runtime, Nginx and Certbot image digests recorded in deploy/.images.env.'
