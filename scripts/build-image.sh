#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_revision="${CLONE_BUILD_REVISION:-$(git -C "$task_root" rev-parse HEAD)}"
[[ "$task_revision" =~ ^[a-f0-9]{40}$ ]] || { echo 'A full commit SHA is required.' >&2; exit 1; }
task_tag="${1:-clone-me:$task_revision}"
[[ "$task_tag" =~ ^[a-zA-Z0-9./:_-]+$ ]] || { echo 'Invalid image tag.' >&2; exit 1; }
[[ -f "$task_root/deploy/.images.env" ]] || bash "$task_root/scripts/freeze-images.sh"
task_runtime="$(sed -n 's/^RUNTIME_IMAGE=//p' "$task_root/deploy/.images.env")"
[[ "$task_runtime" =~ @sha256:[a-f0-9]{64}$ ]] || { echo 'Freeze base image digests first.' >&2; exit 1; }
docker build --build-arg "RUNTIME_IMAGE=$task_runtime" --build-arg "BUILD_REVISION=$task_revision" --tag "$task_tag" "$task_root"
