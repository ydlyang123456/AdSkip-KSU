#!/system/bin/sh
# AdSkip-KSU - action.sh
# 用户手动触发 / 管理器 Action 按钮调用。
# 用法: sh action.sh [update|enable|disable|status|rebuild]  （无参默认 status）

MODDIR=${0%/*}
. "$MODDIR/config.sh"
. "$MODDIR/common/lib.sh"

_cmd="${1:-status}"

case "$_cmd" in
    update)
        # 下载最新清单 -> 就地重新生成 hosts -> 更新时间戳
        if fetch_online; then
            mark_updated
            generate_hosts
            _t=$(grep -cvE '^[[:space:]]*#' "$MODDIR/system/etc/hosts" 2>/dev/null)
            log_msg "info" "update done; hosts regenerated: ${_t:-0} 条"
            echo "在线更新完成，已重新生成 hosts（${_t:-0} 条）。覆盖挂载绑定同一 inode，已即时生效；建议重启或清除 DNS 缓存以获得完整效果。"
        else
            log_msg "warn" "update failed; keeping existing lists"
            echo "在线更新失败，已保留现有清单。详见 $LOG_FILE"
        fi
        ;;
    rebuild)
        # 仅用现有清单重新生成 hosts（不下载）
        generate_hosts
        _t=$(grep -cvE '^[[:space:]]*#' "$MODDIR/system/etc/hosts" 2>/dev/null)
        if [ "$SKIP_ADSDK" = "1" ]; then
            log_msg "info" "rebuild done; hosts regenerated: ${_t:-0} 条（含广告 SDK 域，SKIP_ADSDK=1）"
            echo "已用现有清单重新生成 hosts（${_t:-0} 条）。SKIP_ADSDK=1：已合并广告 SDK 域名增强清单。"
        else
            log_msg "info" "rebuild done; hosts regenerated: ${_t:-0} 条（SKIP_ADSDK=0：未合并广告 SDK 域）"
            echo "已用现有清单重新生成 hosts（${_t:-0} 条）。SKIP_ADSDK=0：未写入任何广告 SDK 域（默认关闭，绝不误伤）。"
        fi
        ;;
    adsdk)
        # v1.1：广告 SDK 域名增强状态查询
        if [ "$SKIP_ADSDK" = "1" ]; then
            echo "SKIP_ADSDK=1 · 广告 SDK 域名增强【已开启】：rebuild 后会把 common/blocklist_adsdk.txt 合并进 hosts。"
        else
            echo "SKIP_ADSDK=0 · 广告 SDK 域名增强【默认关闭】：不向 hosts 写入任何广告 SDK 域（绝不误伤播放域）。"
        fi
        [ -n "$ADSDK_ONLINE_URL" ] && echo "ADSDK_ONLINE_URL=$ADSDK_ONLINE_URL（P1 在线子清单已配置）" || echo "ADSDK_ONLINE_URL=（未配置在线子清单）"
        ;;
    enable)
        # 移除 disable 标记以启用模块
        rm -f "$MODDIR/disable"
        log_msg "info" "module enabled"
        echo "模块已启用，请重启以完全生效。"
        ;;
    disable)
        # 创建 disable 标记以禁用模块（Magisk/KernelSU/APatch 通用约定）
        touch "$MODDIR/disable"
        log_msg "info" "module disabled"
        echo "模块已禁用，请重启以完全生效。"
        ;;
    status)
        if [ "$2" = "--json" ]; then
            print_status_json
        else
            print_status
        fi
        ;;
    *)
        echo "Usage: $0 [update|enable|disable|status|rebuild|adsdk]"
        echo "  update   下载最新清单并重新生成 hosts"
        echo "  rebuild  用现有清单重新生成 hosts（不下载）"
        echo "  enable   启用模块（移除 disable 标记）"
        echo "  disable  禁用模块（创建 disable 标记）"
        echo "  status   显示当前状态（默认）"
        echo "  adsdk    显示广告 SDK 域名增强状态（SKIP_ADSDK）"
        ;;
esac
