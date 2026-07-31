#!/system/bin/sh
# AdSkip-KSU - config.sh
# 纯变量文件，被 post-fs-data.sh / service.sh / action.sh 通过 '.' 引入。
# 只使用 POSIX / busybox 兼容写法，不要写 bash 专属语法。

# IPv4 黑洞地址：被屏蔽域名解析到的地址（默认 0.0.0.0 拒绝连接）。
REDIRECT_IPV4="0.0.0.0"

# IPv6 黑洞地址：配合 IPv4 做双栈屏蔽（默认 :: 拒绝连接）。
REDIRECT_IPV6="::"

# 是否允许在线更新（true / false）。离线时内置清单仍可正常生效。
# v1.2：默认改为 "false"（修复 v1.1 卡顿根因）。
# 原因：v1.1 默认 true 时合并 5 个激进源，downloaded_hosts.txt 达数百万行，
#       无条件追加进 /etc/hosts 拖死 DNS。默认关后仅内置清单生效，卡顿消失。
#       需更强覆盖的用户可在 App「模块」页或本文件手动开启。
ONLINE_UPDATE="false"

# 两次在线更新的最小间隔（小时），防止开机/频繁触发时反复联网。
UPDATE_MIN_AGE_HOURS=24

# v1.2 新增：generate_hosts 生成后去重有效行数超过此阈值记 warn（提示关在线更新 / clearcache）
HOSTS_GUARD_LINES=50000

# 在线 hosts 源（空格分隔的多个 URL）。仅下载数据清单文本，绝不以任何方式执行下载内容。
# 采用更激进的聚合源组合（均为实测 HTTP 200 的可用源），覆盖更广（广告 / 追踪 / 恶意域名）：
#   1. StevenBlack Unified（hosts 格式，基础档，稳定）
#   2. notracking hostnames（纯域名列表，约 5.6 万行，无 IP 前缀，下载时归一化补前缀）
#   3. blocklistproject ads（hosts 格式）
#   4. blocklistproject tracking（hosts 格式）
#   5. blocklistproject malware（hosts 格式）
# fetch_online 会循环遍历逐个下载、任一成功即合并、全部失败则保留旧缓存，并对下载内容做归一化（裸域名补 REDIRECT_IPV4 前缀）；generate_hosts 会去重。
UPDATE_URLS="https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts https://raw.githubusercontent.com/notracking/hosts-blocklists/master/hostnames.txt https://raw.githubusercontent.com/blocklistproject/Lists/master/ads.txt https://raw.githubusercontent.com/blocklistproject/Lists/master/tracking.txt https://raw.githubusercontent.com/blocklistproject/Lists/master/malware.txt"

# 是否关闭系统级私有 DNS（Private DNS / DoH）。默认开。
# 关闭后系统默认解析会走 /system/etc/hosts，配合 hosts 屏蔽更彻底。
# 使用 `settings put global private_dns_mode off` 关闭，best-effort：
#   若 settings 不可用或执行失败，仅记日志、绝不报错退出、绝不影响其他逻辑。
# 注意：settings 在 post-fs-data 阶段通常不可用（依赖系统服务），故由 service.sh 在 boot 后期调用。
# 关闭增强：将此项改为 "false"。
DISABLE_PRIVATE_DNS="true"

# 日志文件路径（模块被刷入后通常位于 /data/adb/modules/adskip_ksu/）。
LOG_FILE="/data/adb/modules/adskip_ksu/action.log"

# 日志级别（debug / info / warn / error），当前仅作为记录档位说明。
LOG_LEVEL="info"

# ============================================================
# v1.1：广告 SDK 独立域名增强拦截（hosts 线，默认关闭）
# ============================================================
# 仅当 SKIP_ADSDK="1" 时，action.sh rebuild / post-fs-data.sh 生成的 hosts 才会
# 合并 common/blocklist_adsdk.txt（纯广告投放/竞价域）。
# 默认 "0"：绝不向 hosts 写入任何广告 SDK 域，满足「默认不误伤播放域」硬约束。
# 绝不误伤：blocklist_adsdk.txt 只收广告 SDK 服务端域，与 blocklist.txt（319 条原契约）独立，
# 严禁收录同名内容域 / 播放流 / 音乐 CDN（见 docs/ADSDK_REGRESSION.md）。
SKIP_ADSDK="0"

# P1：广告 SDK 域在线子清单源（空 = 不在线更新）。
# 非空时 fetch_adsdk_online 拉取到 common/adsdk_online.txt，随 rebuild 一并合并。
# 默认空，离线时内置清单仍可正常生效。
ADSDK_ONLINE_URL=""
