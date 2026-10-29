#!/system/bin/sh
set -eu
TRIAL=${0%/*}
case "$TRIAL" in
  /data/user/0/dev.ghostlock.honor/files/direct-run-*|/data/data/dev.ghostlock.honor/files/direct-run-*) ;;
  *) exit 90 ;;
esac
umask 022
exec >>"$TRIAL/handoff.log" 2>&1
early_failure() {
  code=$?
  /system/bin/setenforce 1 || :
  exit "$code"
}
trap early_failure EXIT
test "$(/system/bin/id -u)" = 0 || exit 91
/system/bin/grep -Eq '^Seccomp:[[:space:]]*0$' /proc/self/status || exit 99
test -r /proc/modules || exit 99
if /system/bin/grep -q '^kernelsu[[:space:]]' /proc/modules; then
  echo 'existing_kernel_module=1; refusing duplicate load'
  exit 99
else
  module_status=$?
  test "$module_status" = 1 || exit 99
fi
echo 'privileged_module_absence_verified=1'
case "$DIRECT_BOOT_COUNT" in ''|*[!0-9]*) exit 97 ;; esac
# This root child cannot resolve Binder's settings service on this firmware.
# Read a kernel identity directly; the App compares it again through its own su after loading.
KERNEL_BOOT_ID=$(/system/bin/cat /proc/sys/kernel/random/boot_id) || exit 97
/system/bin/printf '%s\n' "$KERNEL_BOOT_ID" | /system/bin/grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || exit 97
test "$(/system/bin/getprop sys.boot_completed)" = 1 || exit 97
test "$(/system/bin/getprop ro.boot.flash.locked)" = 1 || exit 97
test "$(/system/bin/getprop ro.boot.verifiedbootstate)" = green || exit 97
test "$(/system/bin/uname -r)" = 5.15.180-android13-8-00018-g9b2308ac0ad6-ab14563692 || exit 97
test "$#" = 4 || exit 92
test "$1" = late-load && test "$2" = --kmi && test "$3" = android13-5.15 && test "$4" = --allow-shell || exit 92
case "$DIRECT_KSUD" in /data/app/*/lib/arm64/libksud.so) ;; *) exit 93 ;; esac
set -- $(/system/bin/sha256sum "$DIRECT_KSUD")
test "$1" = 4f7d5fd65631915ea579ff406bd9cbe58217f2c91dac6527f5b2f28ad4508171 || exit 94
for dir in /data/adb/modules /data/adb/modules_update /data/adb/metamodule /data/adb/late-load.d /data/adb/post-mount.d /data/adb/service.d /data/adb/boot-completed.d; do
  if test -d "$dir"; then
    /system/bin/ls -A "$dir" >"$TRIAL/existing-stage.txt"
    test ! -s "$TRIAL/existing-stage.txt" || exit 95
  fi
done
if test -e /data/adb/ksud; then
  set -- $(/system/bin/sha256sum /data/adb/ksud)
  test "$1" = 4f7d5fd65631915ea579ff406bd9cbe58217f2c91dac6527f5b2f28ad4508171 || exit 96
fi
SLOT=$(/system/bin/getprop ro.boot.slot_suffix)
case "$SLOT" in _a|_b) ;; *) exit 97 ;; esac
test "$SLOT" = "$DIRECT_SLOT" || exit 97
/system/bin/sha256sum "/dev/block/by-name/boot$SLOT" "/dev/block/by-name/init_boot$SLOT" "/dev/block/by-name/vbmeta$SLOT" >"$TRIAL/boot-before.sha256"
/system/bin/grep -Fqx "5a97c29279900512d9f3c073423d5904f02bc5cbd4209e1435d9c3cb355c28f9  /dev/block/by-name/boot$SLOT" "$TRIAL/boot-before.sha256" || exit 98
/system/bin/grep -Fqx "5bc8cd5d42d61ebb35418973efa337316d1121ffca46d063120cbc7aa229bc5d  /dev/block/by-name/init_boot$SLOT" "$TRIAL/boot-before.sha256" || exit 98
/system/bin/grep -Fqx "b2cdf6089cee2803ab3d571dbe1e1e04f7e2fb7488c32879a84ad602b6343867  /dev/block/by-name/vbmeta$SLOT" "$TRIAL/boot-before.sha256" || exit 98
/system/bin/sha256sum /data/adb/ksu/.allowlist >"$TRIAL/allowlist-before.sha256"
/system/bin/printf 'uid=0\nseccomp=0\nmodule_absent=1\nparent_boot_count=%s\nslot=%s\nkernel_boot_id=%s\n' "$DIRECT_BOOT_COUNT" "$SLOT" "$KERNEL_BOOT_ID" >"$TRIAL/preload-verified.txt"
echo 'root_uid=0'
echo 'existing_configuration_preserved=1'
exec "$DIRECT_KSUD" late-load --kmi android13-5.15 --allow-shell
