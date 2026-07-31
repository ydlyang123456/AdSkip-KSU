# AdSkip VPN（AdSkip-KSU v1.2 Phase 2 · 独立应用 `com.adskip.vpn`）

## 定位

AdSkip VPN 是 **AdSkip-KSU v1.2 Phase 2** 立项的一个**独立 Android 应用**，包名 `com.adskip.vpn`，
与 manager App（`com.adskip.app`）**完全分离、不合并、不共享代码**。它以「fake-VPN」方式接管设备 DNS，
在本地过滤广告/追踪/SDK 服务端域名，并阻断 DoH 端点，迫使应用回退到受控明文 DNS。

- **独立 APK**：单独构建、单独安装、独立版本（`versionCode=100`、`versionName=1.0.0`）。
- **权限**：仅声明 `INTERNET`（fake-VPN 需建 tun 与上游 UDP 通信）、`FOREGROUND_SERVICE`、
  `FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`。**不**要求任何危险权限。
- **零第三方库**：纯 Android SDK + Java，DNS 报文最小自实现，构建沿用 `build.py`（javac + `android-34.jar` + aapt + apksigner），无 Gradle。

## 机制

采用 **DNS-only 分流隧道**（最稳健，设备其余流量正常联网，无需转发普通流量）：

1. `AdSkipVpnService.Builder` 配置：
   - `addAddress("10.0.0.1", 32)` —— 本地 tun 虚拟地址。
   - `addRoute("10.0.0.1", 32)` —— **仅把 DNS 服务器 IP 路由进 tun**（分流，不捕获 0.0.0.0/0）。
   - `addDnsServer("10.0.0.1")` —— 系统解析器把 DNS 查询发往 tun。
   - `addDisallowedApplication(pkg)`（对每个「放行 App」）—— 这些 App 完全绕过 VPN，用真实 DNS。
2. 系统把 DNS 查询（UDP→10.0.0.1:53）写入 tun fd；`VpnTunnel` 读 `FileInputStream`，解析 IPv4+UDP 头，取出 DNS payload。
3. `DnsProxy` 解析查询域名 → 查 `Blocklist`：
   - **命中黑名单** → 伪造应答（A=0.0.0.0 / AAAA=::）写回 tun，拦截完成。
   - **未命中** → `DnsResolver` 经 `VpnService.protect(socket)` 排除自身后，转发到受控上游明文 DNS（223.5.5.5 / 119.29.29.29），读回响应写回 tun。
4. 仅 DNS 走 tun；其余流量走真实网络 → 设备正常上网，仅 DNS 被过滤。

### DoH 端点阻断（无需 TLS MITM）

`DohBlocklist` 维护已知 DoH 端点域名（`dns.google`、`dns.cloudflare.com`、`dns.quad9.net`、
`dns.alidns.com`、`doh.opendns.com`、`doh.pub`、`rubyfish.cn` 等）。这些域名本身在 DNS 层被
直接解析为 `0.0.0.0`/NXDOMAIN → App 无法连上 DoH → **回退到系统明文 DNS（即被本应用过滤的 tun DNS）**。
无需解密 HTTPS/TLS，仅把 DoH 端点域名纳入黑名单即可「迫使回退受控明文 DNS」。

## 边界（明确不做）

- ❌ 真实隧道到外服（无外部服务器，纯本地过滤）。
- ❌ TLS MITM / HTTPS 解密（不拦截 DoH 的 TLS 内容，仅阻断 DoH 端点域名）。
- ❌ 与正片严格同域混流的广告移除（如 YouTube 同域广告，hosts/VPN 均无法覆盖）。
- ❌ 要求 root（MVP 免 root；root 仅用于「可选导入模块 blocklist」）。

## 与模块 + manager App 协同

| 组件 | 包名 | 职责 | 协同 |
| --- | --- | --- | --- |
| 模块 AdSkip-KSU | （root 侧脚本） | systemless hosts 屏蔽 | 独立 |
| manager App | `com.adskip.app` | 管理模块 + 无障碍跳过（**无 INTERNET**） | 新增「AdSkip VPN」卡片 → **deep-link** 按包名 `com.adskip.vpn` 启动本应用（不内嵌） |
| VPN App | `com.adskip.vpn` | fake-VPN DNS 过滤（**有 INTERNET**） | 可选「从模块导入 blocklist」→ 经 root 读取 `/data/adb/modules/adskip_ksu/common/blocklist.txt`+`blocklist_adsdk.txt` |

- manager App 的「AdSkip VPN」入口卡片通过 `Intent`（`ACTION_MAIN` + 包名 `com.adskip.vpn`）启动本应用
  的 `MainActivity`（已声明 `MAIN` + `LAUNCHER` intent-filter）。未安装则引导安装。
- 本应用「从 AdSkip 模块导入黑名单」为可选入口，需用户授权 root 后读取模块内置清单合并进运行时黑名单；
  MVP 离线内置 `assets/blocklist.txt` 即生效，不依赖 root。

## 单槽位 VPN 冲突说明（Android 硬限制）

Android **仅允许一个**活跃 `VpnService`（单槽位硬限制）。AdSkip VPN 与任何真实 VPN（如梯子类 App）
**不可同时运行**：

- 启动时若 `VpnService.prepare()` 返回非 null（已有其他 VPN），UI 会引导用户前往系统 VPN 授权界面，
  由系统提示「需先断开其他 VPN」——**不静默失败**。
- 主界面实时检测其他 VPN 是否激活，若检测到则显示红色冲突提示「请先断开其他 VPN 再启用 AdSkip VPN」。
- 缓解手段：本应用采用「仅 DNS 分流隧道」+「按 App 放行（addDisallowedApplication）」，
  但仍无法突破 OS 单槽位限制；UI 充分告知，由用户决定。

## 黑名单约束

`assets/blocklist.txt` 为离线内置黑名单，**只收广告/追踪/SDK 服务端域**，**绝不误伤**音乐 App 播放流/
下载/搜索/图片 CDN、同名主站/内容域（对照 `AdSkip-KSU/docs/ADSDK_REGRESSION.md` 红线）。
合并自 `AdSkip-KSU/common/blocklist.txt` 与 `blocklist_adsdk.txt` 去重合并，规模约 351 条。
对黑名单执行 `grep -E 'music\.163\.com|y\.qq\.com|kugou\.com|kuwo\.cn|qbox\.me'` **必须零命中**（已在构建前校验）。

## 安装 / 使用

1. 构建：`python build.py`（或 `bash build.sh`），产出 `AdSkip-KSU/app/AdSkipVPN.apk`。
2. 安装：adb install AdSkipVPN.apk，或从 manager App 的「AdSkip VPN」入口安装/启动。
3. 首次启用：打开 AdSkip VPN → 打开「启用 AdSkip VPN」开关 → 系统弹出 VPN 授权 → 允许。
   （若提示需先断开其他 VPN，请先断开再启用。）
4. 主界面可查看实时拦截次数、按 App 勾选「放行/拦截」、开关 DoH 端点阻断。
5. 可选：点击「从 AdSkip 模块导入黑名单」经 root 合并模块清单。

> 默认 opt-in：应用不会自动启用，需用户手动开启；统计与策略持久化于本应用私有 SharedPreferences。
