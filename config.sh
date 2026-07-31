#!/system/bin/sh
# AdSkip-KSU - config.sh
# 纯变量文件，被 post-fs-data.sh / service.sh / action.sh 通过 '.' 引入。
# 只使用 POSIX / busybox 兼容写法，不要写 bash 专属语法。

# IPv4 黑洞地址：被屏蔽域名解析到的地址（默认 0.0.0.0 拒绝连接）。
REDIRECT_IPV4="0.0.0.0"

# IPv6 黑洞地址：配合 IPv4 做双栈屏蔽（默认 :: 拒绝连接）。
REDIRECT_IPV6="::"

# 是否允许在线更新（true / false）。离线时内置清单仍可正常生效。
ONLINE_UPDATE="true"

# 两次在线更新的最小间隔（小时），防止开机/频繁触发时反复联网。
UPDATE_MIN_AGE_HOURS=24

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
