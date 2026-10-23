#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_archive="${1:?Usage: restore.sh backup.zip new-directory [application.jar]}"
task_destination="${2:?Specify a new restore directory}"
task_jar="${3:-$task_root/backend/target/clone-me-0.1.0-SNAPSHOT.jar}"
[[ ! -e "$task_destination" ]] || { echo 'Restore destination must not exist.' >&2; exit 1; }
java --enable-native-access=ALL-UNNAMED -Xmx256m -jar "$task_jar" --restore-backup "$task_archive" "$task_destination"
