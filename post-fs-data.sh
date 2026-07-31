#!/system/bin/sh
# AdSkip-KSU - post-fs-data.sh
# 开机早期执行：生成最终的 systemless hosts。
# 不调用任何 root 管理器专属二进制，仅用标准 shell + getprop，故 KernelSU/Magisk/APatch 通用。

MODDIR=${0%/*}
. "$MODDIR/config.sh"
. "$MODDIR/common/lib.sh"

# 版本守卫：要求 Android 10+（SDK >= 29）。低于则跳过并记日志。
_sdk=$(getprop ro.build.version.sdk 2>/dev/null)
if [ -z "$_sdk" ] || [ "$_sdk" -lt 29 ]; then
    log_msg "warn" "unsupported Android SDK '$_sdk' (<29); skipping hosts generation"
    exit 0
fi

# 管理器检测（仅日志，不影响行为）
detect_manager

# 就地生成最终 hosts（保持 inode，覆盖挂载即时生效）
generate_hosts

_total=$(grep -cvE '^[[:space:]]*#' "$MODDIR/system/etc/hosts" 2>/dev/null)
log_msg "info" "hosts regenerated: ${_total:-0} 条"
