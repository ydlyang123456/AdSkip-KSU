#!/system/bin/sh
# AdSkip-KSU - service.sh
# 开机后期执行：可选的后台在线更新（增量刷新缓存，下次重启生效）。
# 本脚本对最终 hosts 的生成不是必须的（post-fs-data.sh 已保证离线兜底）。

MODDIR=${0%/*}
. "$MODDIR/config.sh"
. "$MODDIR/common/lib.sh"

if [ "$ONLINE_UPDATE" = "true" ]; then
    if should_update; then
        # 后台执行（& 已保证不阻塞开机）；_fetch_one 内每个 URL 带 --max-time 60，总时长有界，
        # 因此无需外部 timeout 包裹。注意：timeout 是外部二进制，无法 exec 一个 shell 函数 fetch_online，
        # 在带 timeout 的系统上会导致 "failed to run command 'fetch_online': No such file or directory" 而静默失败。
        ( fetch_online && mark_updated ) &
        log_msg "info" "background online update started"
    else
        log_msg "info" "online update skipped (within min-age window)"
    fi
else
    log_msg "info" "online update disabled in config.sh"
fi

# 关闭系统级私有 DNS（DoH），配合 hosts 屏蔽；best-effort（settings 不可用时仅记日志跳过）。
apply_private_dns

# 自动安装配套 App（best-effort，root 下 pm install）
if [ -f "$MODDIR/app/AdSkipManager.apk" ] && command -v pm >/dev/null 2>&1; then
    pm install -r "$MODDIR/app/AdSkipManager.apk" >/dev/null 2>&1 || log_msg warn "配套 App 安装跳过（可手动安装 app/AdSkipManager.apk）"
fi
