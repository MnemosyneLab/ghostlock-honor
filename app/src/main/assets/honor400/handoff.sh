#!/system/bin/sh
# The host owns post-load verification: this shell may lose exec permission when
# KernelSU restores enforcing. Do not probe or change policy after exec below.
TRIAL=${0%/*}
case "$TRIAL" in /data/local/tmp/ghostlock-honor-run-*) ;; *) exit 90 ;; esac
umask 022
exec >>"$TRIAL/handoff.log" 2>&1
restore_on_early_failure() {
  code=$?
  echo "early_exit=$code"
  /system/bin/setenforce 1
}
trap restore_on_early_failure EXIT
echo "root_uid=$(/system/bin/id -u)"
if [ "$(/system/bin/id -u)" != 0 ]; then exit 91; fi
if [ "$#" -ne 4 ] || [ "$1" != late-load ] || [ "$2" != --kmi ] || [ "$3" != android13-5.15 ] || [ "$4" != --allow-shell ]; then
  echo 'unexpected_arguments=1'
  exit 92
fi
# Keep the existing KernelSU working directory, allowlist and app profiles.
# Third-party module/stage execution is outside this validated recovery scope.
for dir in /data/adb/modules /data/adb/modules_update /data/adb/metamodule /data/adb/late-load.d /data/adb/post-mount.d /data/adb/service.d /data/adb/boot-completed.d; do
  if [ -d "$dir" ]; then
    /system/bin/ls -A "$dir" >"$TRIAL/existing-stage.txt" 2>"$TRIAL/existing-stage.error"
    if [ "$?" -ne 0 ] || [ -s "$TRIAL/existing-stage.txt" ]; then
      echo "existing_module_or_stage_refused=$dir"
      exit 93
    fi
  fi
done
if [ -e /data/adb/ksud ]; then
  current=$(/system/bin/sha256sum /data/adb/ksud)
  current=${current%% *}
  if [ "$current" != 4f7d5fd65631915ea579ff406bd9cbe58217f2c91dac6527f5b2f28ad4508171 ]; then
    echo 'installed_daemon_version_mismatch=1'
    exit 94
  fi
fi
if [ -f /data/adb/ksu/.allowlist ]; then
  /system/bin/sha256sum /data/adb/ksu/.allowlist >"$TRIAL/allowlist-before.sha256"
fi
echo 'existing_configuration_preserved=1'
echo 'starting_official_late_load=1'
exec "$TRIAL/ksud" "$@"
