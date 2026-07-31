#!/system/bin/sh
# AdSkip-KSU - common/lib.sh
# 被 post-fs-data.sh / service.sh / action.sh 共享引入的公共函数。
# 仅使用 POSIX / busybox 兼容语法（无 bash 数组、无 [[ 、函数用 name() 形式）。
# 调用前必须已设置：MODDIR（模块目录）与已 source config.sh。

# ---- 日志 ----
log_msg() {
    _lvl="$1"
    shift
    _msg="$*"
    _ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
    _logdir=$(dirname "$LOG_FILE" 2>/dev/null)
    mkdir -p "$_logdir" 2>/dev/null
    echo "[$_ts][$_lvl] $_msg" >> "$LOG_FILE" 2>/dev/null
}

# ---- Root 管理器检测（仅打日志，不影响任何行为） ----
detect_manager() {
    for _m in ksu magisk apatch; do
        if [ -d "/data/adb/$_m" ]; then
            log_msg "info" "detected root manager marker: /data/adb/$_m"
        fi
    done
}

# ---- 将单个 blocklist 文件逐行注入到 _out（双栈黑洞，skip 注释/空行/畸形行） ----
# 参数：$1 = blocklist 文件路径
_emit_blocklist() {
    _bf="$1"
    [ -f "$_bf" ] || return 0
    while IFS= read -r _line || [ -n "$_line" ]; do
        _domain=$(printf '%s' "$_line" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        [ -z "$_domain" ] && continue
        case "$_domain" in
            \#*) continue ;;
        esac
        # 跳过含空格的畸形行（应为纯域名）
        case "$_domain" in
            *" "*) continue ;;
        esac
        echo "$REDIRECT_IPV4 $_domain" >> "$_out"
        echo "$REDIRECT_IPV6 $_domain" >> "$_out"
    done < "$_bf"
}

# ---- 就地生成最终 hosts（保持同一 inode，避免覆盖挂载失效） ----
generate_hosts() {
    _out="$MODDIR/system/etc/hosts"
    _blk="$MODDIR/common/blocklist.txt"
    _dl="$MODDIR/common/downloaded_hosts.txt"
    _adsdk="$MODDIR/common/blocklist_adsdk.txt"
    _adsdk_online="$MODDIR/common/adsdk_online.txt"
    mkdir -p "$MODDIR/system/etc" 2>/dev/null

    # 文件头 + 基础行（用 > 截断同一 inode 写入）
    {
        echo "# AdSkip-KSU auto-generated hosts"
        echo "# Generated: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)"
        if [ "$SKIP_ADSDK" = "1" ]; then
            echo "# Sources: 内置清单 blocklist.txt + 广告 SDK 清单 blocklist_adsdk.txt（SKIP_ADSDK=1）+ 缓存在线清单"
        else
            echo "# Sources: 内置清单 blocklist.txt + 缓存的在线清单（SKIP_ADSDK=0：未合并广告 SDK 域）"
        fi
        echo "# 请勿手动编辑本文件，改 config.sh / blocklist.txt 后由脚本重新生成。"
        echo ""
        echo "127.0.0.1 localhost"
        echo "::1 localhost"
    } > "$_out"

    # 处理内置 blocklist（319 条原契约，恒生效）
    _emit_blocklist "$_blk"

    # v1.1：仅在 SKIP_ADSDK=1 时合并广告 SDK 独立清单（绝不误伤播放域；默认关闭）
    if [ "$SKIP_ADSDK" = "1" ]; then
        _emit_blocklist "$_adsdk"
        # P1：合并 SDK 在线子清单缓存（若存在且非空）
        if [ -s "$_adsdk_online" ]; then
            _emit_blocklist "$_adsdk_online"
            log_msg "info" "generate_hosts: merged blocklist_adsdk.txt + adsdk_online.txt (SKIP_ADSDK=1)"
        else
            log_msg "info" "generate_hosts: merged blocklist_adsdk.txt (SKIP_ADSDK=1)"
        fi
    else
        log_msg "info" "generate_hosts: SKIP_ADSDK=0, adsdk domains NOT written to hosts"
    fi

    # v1.2：追加缓存的在线清单（已是 hosts 格式，原样追加）。
    # 双重门控（设计 §二 Model B）：仅当「在线更新开启」且「缓存为在线态(.dl_online 标记存在)」
    # 且「缓存文件非空」三者同时满足才追加；否则不追加。
    # 修复 v1.1 的 bug：v1.1 仅判断 -s 非空即追加，导致 ONLINE_UPDATE=false 关掉更新后，
    # 磁盘上残留的旧缓存（数百万行）仍被写进 hosts，卡顿依旧。
    # 默认 ONLINE_UPDATE=false → 不追加 → 旧缓存不再生效，卡顿修复。
    : "${HOSTS_GUARD_LINES:=50000}"
    if [ "$ONLINE_UPDATE" = "true" ] && [ -f "$MODDIR/common/.dl_online" ] && [ -s "$_dl" ]; then
        tr -d '\r' < "$_dl" >> "$_out"
        log_msg "info" "generate_hosts: appended online cache (ONLINE_UPDATE=true, online-state)"
    else
        log_msg "info" "generate_hosts: online cache NOT appended (ONLINE_UPDATE=$ONLINE_UPDATE, marker=$( [ -f "$MODDIR/common/.dl_online" ] && echo yes || echo no ))"
    fi

    # 整体去重（跳过空行，awk 保留首次出现顺序），写回同一 inode，保证幂等
    _tmp="$MODDIR/system/etc/.hosts.tmp"
    awk 'NF>0 && !seen[$0]++' "$_out" > "$_tmp" 2>/dev/null
    if [ -s "$_tmp" ]; then
        cat "$_tmp" > "$_out"
    fi
    rm -f "$_tmp" 2>/dev/null

    # v1.2：护栏——统计去重后有效行数（跳过 # 注释与空行）；超过阈值记 warn。
    # 防止超大 hosts（如误开 ONLINE_UPDATE 且拉到数百万域）拖死 DNS，给出明确提示。
    _n=$(grep -cvE '^[[:space:]]*#|^[[:space:]]*$' "$_out" 2>/dev/null)
    if [ -n "$_n" ] && [ "$_n" -gt "$HOSTS_GUARD_LINES" ]; then
        log_msg "warn" "hosts line count $_n exceeds safe threshold $HOSTS_GUARD_LINES; 建议关闭 ONLINE_UPDATE 或执行 clearcache 以恢复 DNS 性能"
    fi
}

# ---- 下载单个 URL 并追加到目标文件 ----
_fetch_one() {
    _url="$1"
    _dst="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --connect-timeout 10 --max-time 60 "$_url" >> "$_dst" 2>/dev/null && return 0
    fi
    if command -v wget >/dev/null 2>&1; then
        wget -q -T 10 -O - "$_url" >> "$_dst" 2>/dev/null && return 0
    fi
    return 1
}

# ---- 拉取全部在线源，刷新 downloaded_hosts.txt（失败则保留旧文件） ----
fetch_online() {
    _dl="$MODDIR/common/downloaded_hosts.txt"
    _tmp="$MODDIR/common/.dl.tmp"
    : > "$_tmp"
    _got=0
    for _url in $UPDATE_URLS; do
        if _fetch_one "$_url" "$_tmp"; then
            _got=1
            log_msg "info" "fetched: $_url"
        else
            log_msg "warn" "fetch failed: $_url"
        fi
    done
    if [ "$_got" -eq 1 ]; then
        # 归一化下载内容后写入 downloaded_hosts.txt：
        #   1) tr -d '\r' 去除回车；
        #   2) 跳过空行与以 # 开头的注释行；
        #   3) 已以 IP 开头的 hosts 行（如 0.0.0.0 / ::1 ）原样保留；
        #   4) 裸域名（notracking 等纯域名源，可能带首尾空白）trim 后补 REDIRECT_IPV4 前缀，
        #      确保写入 /etc/hosts 后合法生效（hosts 要求 "IP 域名"，裸 domain 行无效）。
        # 注意：归一化仅针对在线下载内容、且只补 IPv4；内置 blocklist 的双栈逻辑不受影响。
        tr -d '\r' < "$_tmp" | awk -v ip="$REDIRECT_IPV4" '
            $0 == "" { next }
            $0 ~ /^#/ { next }
            { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0); if ($0 == "") next }
            $1 ~ /^[0-9a-fA-F:.]+$/ { print $0; next }
            { print ip" "$0 }
        ' > "$_dl"
        rm -f "$_tmp" 2>/dev/null
        # v1.2：标记缓存为「在线态」——generate_hosts 仅在该标记存在时才追加在线缓存。
        # 这样 clearcache 删除标记后（即使 downloaded_hosts.txt 非空）也不会再被追加。
        : > "$MODDIR/common/.dl_online"
        log_msg "info" "online update complete (marked .dl_online)"
        return 0
    fi
    rm -f "$_tmp" 2>/dev/null
    # v1.2：拉取失败 → 清除在线态标记，使残留的旧缓存「非在线态」，不再被 generate_hosts 追加。
    #        保留 downloaded_hosts.txt 本身，避免无谓清空（下次成功更新会覆盖）。
    rm -f "$MODDIR/common/.dl_online"
    log_msg "warn" "online update failed, removed .dl_online marker (cache stays offline-state)"
    return 1
}

# ---- P1：拉取广告 SDK 在线子清单，刷新 adsdk_online.txt（失败则保留旧文件） ----
# 仅当 config.sh 的 ADSDK_ONLINE_URL 非空时有效；多源容错沿用 _fetch_one 范式。
fetch_adsdk_online() {
    [ -n "$ADSDK_ONLINE_URL" ] || { log_msg "info" "ADSDK_ONLINE_URL empty, skip adsdk online fetch"; return 1; }
    _dst="$MODDIR/common/adsdk_online.txt"
    _tmp="$MODDIR/common/.adsdk.tmp"
    : > "$_tmp"
    if _fetch_one "$ADSDK_ONLINE_URL" "$_tmp"; then
        # 归一化：去回车、跳注释/空行、已带 IP 前缀的原样保留、裸域名补 REDIRECT_IPV4 前缀。
        tr -d '\r' < "$_tmp" | awk -v ip="$REDIRECT_IPV4" '
            $0 == "" { next }
            $0 ~ /^#/ { next }
            { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0); if ($0 == "") next }
            $1 ~ /^[0-9a-fA-F:.]+$/ { print $0; next }
            { print ip" "$0 }
        ' > "$_dst"
        rm -f "$_tmp" 2>/dev/null
        log_msg "info" "adsdk online list updated"
        return 0
    fi
    rm -f "$_tmp" 2>/dev/null
    log_msg "warn" "adsdk online fetch failed, keeping cached list"
    return 1
}

# ---- 关闭系统级私有 DNS（Private DNS / DoH），best-effort ----
# 关闭后系统默认解析会走 /system/etc/hosts，配合 hosts 屏蔽更彻底。
# 仅当 DISABLE_PRIVATE_DNS="true" 时执行；settings 不可用或失败都仅记日志、绝不退出、绝不影响其他逻辑。
# 注意：settings 在 post-fs-data 阶段通常不可用（依赖系统服务），故由 service.sh 在 boot 后期调用。
apply_private_dns() {
    [ "$DISABLE_PRIVATE_DNS" = "true" ] || return 0
    if command -v settings >/dev/null 2>&1; then
        settings put global private_dns_mode off 2>/dev/null || log_msg "warn" "关闭私有 DNS 失败，忽略"
    else
        log_msg "warn" "settings 不可用，跳过关闭私有 DNS"
    fi
}

# ---- 是否应执行在线更新（节流判断） ----
should_update() {
    [ "$ONLINE_UPDATE" = "true" ] || return 1
    _lu="$MODDIR/common/.last_update"
    [ ! -f "$_lu" ] && return 0
    _last=$(cat "$_lu" 2>/dev/null)
    _now=$(date +%s 2>/dev/null)
    [ -z "$_last" ] && return 0
    _min=$(( UPDATE_MIN_AGE_HOURS * 3600 ))
    [ $(( _now - _last )) -ge "$_min" ] && return 0
    return 1
}

# ---- 记录本次成功更新时间 ----
mark_updated() {
    date +%s > "$MODDIR/common/.last_update" 2>/dev/null
}

# ---- 打印状态（action.sh status 用） ----
print_status() {
    _blk="$MODDIR/common/blocklist.txt"
    _dl="$MODDIR/common/downloaded_hosts.txt"
    _lu="$MODDIR/common/.last_update"
    echo "=== AdSkip-KSU status ==="
    echo "module dir     : $MODDIR"
    echo "redirect IPv4  : $REDIRECT_IPV4"
    echo "redirect IPv6  : $REDIRECT_IPV6"
    echo "online update  : $ONLINE_UPDATE (min age ${UPDATE_MIN_AGE_HOURS}h)"
    _bd=$(grep -vcE '^[[:space:]]*#|^[[:space:]]*$' "$_blk" 2>/dev/null)
    echo "blocklist count: ${_bd:-0} domains"
    _dd=$(grep -vcE '^[[:space:]]*#|^[[:space:]]*$' "$_dl" 2>/dev/null)
    echo "cached online  : ${_dd:-0} entries"
    if [ -f "$_lu" ]; then
        _lt=$(cat "$_lu" 2>/dev/null)
        echo "last update    : $(date -d "@$_lt" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$_lt")"
    else
        echo "last update    : never"
    fi
    if [ -f "$MODDIR/disable" ]; then
        echo "enabled        : NO (disable flag present)"
    else
        echo "enabled        : YES"
    fi
    # v1.2：缓存态（同义计算，供文本状态展示）
    _onlineCacheActive="false"
    if [ "$ONLINE_UPDATE" = "true" ] && [ -f "$MODDIR/common/.dl_online" ] && [ -s "$_dl" ]; then
        _onlineCacheActive="true"
    fi
    _staleCache="false"
    if [ -s "$_dl" ] && { [ "$ONLINE_UPDATE" != "true" ] || [ ! -f "$MODDIR/common/.dl_online" ]; }; then
        _staleCache="true"
    fi
    echo "online cache active: $_onlineCacheActive"
    echo "stale cache        : $_staleCache"
    detect_manager
}

# ---- 打印状态 JSON（action.sh status --json 用，供 App / 管理器读取） ----
# 字段: enabled / manager / listCount / lastUpdate / onlineUpdate / disablePrivateDns / version
# POSIX 兼容：用 printf 拼 JSON，避免 bash 专属语法与数组。
print_status_json() {
    _blk="$MODDIR/common/blocklist.txt"
    _dl="$MODDIR/common/downloaded_hosts.txt"
    _lu="$MODDIR/common/.last_update"

    # enabled：是否存在 disable 标记
    if [ -f "$MODDIR/disable" ]; then
        _enabled="false"
    else
        _enabled="true"
    fi

    # manager：检测 root 管理器（取第一个命中的标记）
    if [ -d /data/adb/ksu ]; then
        _manager="KernelSU"
    elif [ -d /data/adb/magisk ]; then
        _manager="Magisk"
    elif [ -d /data/adb/apatch ]; then
        _manager="APatch"
    else
        _manager="unknown"
    fi

    # listCount：内置清单 + 在线缓存 域名数之和
    # 注意：grep -c 在 0 匹配时仍会输出 "0" 且以非 0 退出，故不能再用 "|| echo 0"（会重复输出）。
    # 文件不存在时 grep 无输出，用 ${VAR:-0} 兜底即可。
    _bd=$(grep -vcE '^[[:space:]]*#|^[[:space:]]*$' "$_blk" 2>/dev/null)
    _dd=$(grep -vcE '^[[:space:]]*#|^[[:space:]]*$' "$_dl" 2>/dev/null)
    _listCount=$(( ${_bd:-0} + ${_dd:-0} ))

    # lastUpdate：来自 .last_update 时间戳（best-effort 格式化）
    if [ -f "$_lu" ]; then
        _lt=$(cat "$_lu" 2>/dev/null)
        _lastUpdate=$(date -d "@$_lt" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$_lt")
    else
        _lastUpdate="never"
    fi

    # onlineUpdate / disablePrivateDns 直接取自 config.sh（值为 true / false）
    _onlineUpdate="$ONLINE_UPDATE"
    _disablePrivateDns="$DISABLE_PRIVATE_DNS"

    # v1.2：缓存态字段（供 App 判断是否「在线态 / 陈旧缓存」）
    #   onlineCacheActive：仅当「在线更新开」且「缓存为在线态(.dl_online)」且「缓存非空」三者齐备才 true
    #   cacheLines：downloaded_hosts.txt 的有效行数（非注释/空行）
    #   staleCache：缓存非空 且（在线更新未开 或 非在线态）→ 陈旧缓存，提示清理
    _onlineCacheActive="false"
    if [ "$ONLINE_UPDATE" = "true" ] && [ -f "$MODDIR/common/.dl_online" ] && [ -s "$_dl" ]; then
        _onlineCacheActive="true"
    fi
    _cacheLines=${_dd:-0}
    _staleCache="false"
    if [ -s "$_dl" ] && { [ "$ONLINE_UPDATE" != "true" ] || [ ! -f "$MODDIR/common/.dl_online" ]; }; then
        _staleCache="true"
    fi

    # version：来自 module.prop 的 version 字段
    _version=$(grep '^version=' "$MODDIR/module.prop" 2>/dev/null | head -n1 | cut -d= -f2-)
    [ -z "$_version" ] && _version="unknown"

    printf '{"enabled":%s,"manager":"%s","listCount":%s,"lastUpdate":"%s","onlineUpdate":%s,"disablePrivateDns":%s,"version":"%s","onlineCacheActive":%s,"cacheLines":%s,"staleCache":%s}\n' \
        "$_enabled" "$_manager" "$_listCount" "$_lastUpdate" "$_onlineUpdate" "$_disablePrivateDns" "$_version" "$_onlineCacheActive" "$_cacheLines" "$_staleCache"
}
