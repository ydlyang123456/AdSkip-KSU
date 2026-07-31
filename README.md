# AdSkip-KSU · 跨管理器系统级广告屏蔽模块

> 基于 **systemless hosts** 的 Android 广告 / 追踪 / 恶意域名屏蔽模块，
> 一套代码同时兼容 **KernelSU（GKI / LKM）**、**Magisk**、**APatch** 三家 root 管理器。

---

## 一、原理简介

模块通过模块自带的 `system/etc/hosts` 覆盖挂载，在系统解析域名之前把广告、追踪、
恶意域名指向黑洞地址（`0.0.0.0` 与 `::`），实现**双栈（IPv4 + IPv6）**屏蔽。

- 采用 **Magisk 模块风格**（`module.prop` + `post-fs-data.sh` + `service.sh` + `action.sh` + `system/` 覆盖目录）。
- **不调用任何管理器专属二进制**（如 `magisk` / `ksu` / `apd`），只使用标准 shell + `getprop`；
  管理器检测仅用于打日志，因此三家管理器都能识别并加载。
- 规则来源 = **内置静态清单**（`common/blocklist.txt`）+ 开机/手动**合并在线拉取的最新清单**
  （`common/downloaded_hosts.txt`）。在线失败时**离线兜底**依然生效，绝不因下载失败而失效。

---

## 二、支持的 root 管理器与系统版本

| 项目 | 支持范围 |
| --- | --- |
| Root 管理器 | KernelSU（GKI 2.0 / LKM）、Magisk、APatch |
| Android 版本 | Android 10 – 17（基于 SDK ≥ 29 守卫，hosts 方式天然跨版本） |
| 厂商 ROM | 含小米澎湃OS 3（按已发布版本对待，无需特殊处理） |

> 低于 Android 10（SDK < 29）时，`post-fs-data.sh` 会跳过生成并写日志，不影响系统。

---

## 三、安装步骤（三家管理器通用）

1. 将本仓库打包为 zip（或直接把 `AdSkip-KSU/` 目录整体打包成 `AdSkip-KSU.zip`）。
2. 打开你的 root 管理器 → **模块 / Modules** 页面。
3. 选择 **从本地安装 / Install from storage**。
4. 选中 `AdSkip-KSU.zip`，等待刷入完成。
5. **重启设备**生效。

> 重启后 `post-fs-data.sh` 会自动生成完整屏蔽列表并覆盖挂载到 `/system/etc/hosts`。

---

## 四、使用与在线更新

手动触发（或管理器 Action 按钮）执行：

```sh
sh /data/adb/modules/adskip_ksu/action.sh update     # 下载最新清单并重新生成 hosts
sh /data/adb/modules/adskip_ksu/action.sh rebuild    # 仅用现有清单重新生成（不下载）
sh /data/adb/modules/adskip_ksu/action.sh status     # 查看当前状态
sh /data/adb/modules/adskip_ksu/action.sh enable     # 启用模块
sh /data/adb/modules/adskip_ksu/action.sh disable    # 禁用模块
```

- `update` 采用**就地写同一 inode**，覆盖挂载绑定后立即生效；建议重启或清除 DNS 缓存以获得完整效果。
- 开机后期 `service.sh` 会在后台按 `UPDATE_MIN_AGE_HOURS` 节流地增量更新缓存，下次重启生效。

---

## 五、自定义

编辑 `config.sh`：

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `REDIRECT_IPV4` | IPv4 黑洞地址 | `0.0.0.0` |
| `REDIRECT_IPV6` | IPv6 黑洞地址 | `::` |
| `ONLINE_UPDATE` | 是否允许在线更新 | `false`（v1.2 起默认关，修复卡顿根因） |
| `UPDATE_MIN_AGE_HOURS` | 两次在线更新最小间隔（小时） | `24` |
| `UPDATE_URLS` | 在线 hosts 源（空格分隔多个 URL） | StevenBlack Unified + notracking + blocklistproject ads/tracking/malware（5 个实测可用、更激进聚合源） |
| `DISABLE_PRIVATE_DNS` | 是否关闭系统级私有 DNS（Private DNS / DoH） | `true` |
| `SKIP_ADSDK` | v1.1 广告 SDK 域名增强开关（rebuild 后合并 `blocklist_adsdk.txt`） | `0`（默认关，绝不误伤播放域） |
| `ADSDK_ONLINE_URL` | v1.1(P1) 广告 SDK 在线子清单源（空=不在线更新） | ``（空） |
| `LOG_FILE` | 日志路径 | `/data/adb/modules/adskip_ksu/action.log` |

- 想屏蔽自有域名：直接往 `common/blocklist.txt` 追加（每行一个纯域名，可用 `#` 注释）。
- 想改屏蔽地址（如改为 `127.0.0.1`）：改 `REDIRECT_IPV4` / `REDIRECT_IPV6` 即可。

> 安全约束：脚本**只下载数据清单文本并作为文本追加**，**绝不以任何方式执行下载内容**。

### 增强说明（默认已开启）

模块默认开启两项增强以获得更彻底的屏蔽：

1. **更激进的多源在线更新（v1.2 起默认关）**
   v1.1 默认 `ONLINE_UPDATE="true"`；**v1.2 起默认改为 `"false"`**（修复卡顿根因，详见 §十一）。如需更强覆盖可手动开启。且 `UPDATE_URLS` 已替换为 **5 个实测可用（HTTP 200）、更激进的 hosts 聚合源**，覆盖更广（广告 / 追踪 / 恶意）：
   - StevenBlack Unified（hosts 格式，基础档）
   - notracking hostnames（**纯域名**列表，约 5.6 万行，无 IP 前缀）
   - blocklistproject ads（hosts 格式）
   - blocklistproject tracking（hosts 格式）
   - blocklistproject malware（hosts 格式）
   `fetch_online` 会**循环遍历上述源逐个下载、任一成功即合并、全部失败则保留旧缓存**（best-effort），
   多源内容统一存入 `common/downloaded_hosts.txt`，生成时由 `awk` 去重，互不冲突。

   > **纯域名源会归一化为合法 hosts 条目**：notracking 等纯域名列表没有 `IP 域名` 前缀，
   > 下载时 `fetch_online` 会做归一化——去除 `\r`、跳过空行与 `#` 注释、已以 IP 开头的行原样保留、
   > 裸域名补 `$REDIRECT_IPV4 ` 前缀（如 `0.0.0.0 example.com`），确保写入 `/etc/hosts` 后合法生效。

   > **组合更狠、代价须知**：
   > - 组合更大、屏蔽更狠，但最终 hosts 文件会**显著变大**（可能达**数十万行**）；
   >   系统解析开销与内存/磁盘占用随之增加，属**预期**行为。
   > - 个别正常域名可能被**误杀**：若某 App / 网站异常，可**缩减源数量**以平衡覆盖与误杀。
   > - 最终效果以真机 `sh /data/adb/modules/adskip_ksu/action.sh update` **实测为准**
   >   （沙箱通常无外网，无法在此验证下载）。

2. **关闭系统级私有 DNS（Private DNS / DoH）（默认开）**
   默认 `DISABLE_PRIVATE_DNS="true"`。开启后会在开机后期的 `service.sh` 阶段执行
   `settings put global private_dns_mode off`，让系统默认解析**走 `/system/etc/hosts`**，配合 hosts 屏蔽更彻底。
   该操作 **best-effort**：若 `settings` 命令不可用或执行失败，仅记日志、绝不报错退出、绝不影响其他逻辑。
   > 说明：`settings` 依赖系统服务，在 `post-fs-data` 阶段通常不可用，故放在 `service.sh`（late_start 服务阶段）执行。

3. **仍需注意的局限**
   App **内置 DoH / 自家 DNS-over-TLS** 等会自行解析、可能绕过系统 `hosts`，此钩子**只关闭系统级 Private DNS**，
   无法覆盖 App 自建的加密 DNS 通道。

4. **如何关闭这两个增强**
   - 关闭多源在线更新：在 `config.sh` 中将 `ONLINE_UPDATE="false"`（离线内置清单仍生效）。
   - 关闭私有 DNS 关闭钩子：在 `config.sh` 中将 `DISABLE_PRIVATE_DNS="false"`。

---

## 五之一、v1.1 去广告增强（双管齐下）

v1.1 在保留 systemless hosts 屏蔽的基础上，**新增两条互不依赖的增强线**，且**默认全部关闭**，遵循「默认不误伤 + 最小惊讶」：

| 增强线 | 机制 | 默认 | 开关 | 生效方式 |
| --- | --- | --- | --- | --- |
| **① 广告 SDK 域名增强（hosts 线）** | 把纯广告投放/竞价 SDK 域名追加进 hosts 黑洞 | **关闭** | `SKIP_ADSDK`（模块变量，root 写回） | `action.sh rebuild` / 开机 `post-fs-data.sh` 重新生成 hosts |
| **② 无障碍开屏自动跳过（App 线）** | `AccessibilityService` 监听开屏，命中「跳过」按钮即点掉 | **关闭** | `ENABLE_SKIP`（App 本地 SharedPreferences，免 root） | 用户在系统无障碍中授权后即时生效 |

### 1. 广告 SDK 域名增强（默认关闭，绝不误伤播放域）

- 广告 SDK 域**独立落到 `common/blocklist_adsdk.txt`**，与 `common/blocklist.txt`（319 条原契约）完全分离；**`blocklist.txt` 保持不变**。
- 由 `config.sh` 的 `SKIP_ADSDK` 门控：
  - `SKIP_ADSDK="0"`（**默认**）：`generate_hosts` **绝不**向 hosts 写入任何广告 SDK 域——满足「默认不误伤音乐 App 播放域/内容域/CDN」硬约束。
  - `SKIP_ADSDK="1"`：`rebuild` 后 hosts 会在 319 条基础上**增量**合并 `blocklist_adsdk.txt`（P1 还会合并在线子清单 `adsdk_online.txt`）。
- `blocklist_adsdk.txt` **只收广告投放/竞价 SDK 服务端域**（穿山甲 / 广点通 / 快手 / 百度 / 小米 / 阿里妈妈 / InMobi / Unity / AppLovin / Mintegral / ironSource 等），**绝不收录**任何音乐 App 的播放流 / 下载 / 搜索 / 图片 CDN（见 `docs/ADSDK_REGRESSION.md` 误伤禁用域与回归口径）。
- 误伤豁免：若某 App 因 adsdk 域被误伤，从 `blocklist_adsdk.txt` 移除该域，或直接 `SKIP_ADSDK=0`（整条 hosts 线关闭、不影响无障碍线）。

**如何开启（hosts 线）：**

```sh
# 1) 写入开关（仅白名单键 + 值格式校验，安全）
sh /data/adb/modules/adskip_ksu/action.sh   # 用配套 App 的「模块」页开关，或：
# 经 App：模块页新增的 SKIP_ADSDK 开关 → setConfig("SKIP_ADSDK","1") + 自动 rebuild

# 2) 重新生成 hosts（合并 adsdk 清单）
sh /data/adb/modules/adskip_ksu/action.sh rebuild

# 3) 查看状态
sh /data/adb/modules/adskip_ksu/action.sh adsdk
```

> P1：若 `config.sh` 的 `ADSDK_ONLINE_URL` 非空，`service.sh` 开机后期会 best-effort 拉取在线子清单到 `common/adsdk_online.txt` 并合并；默认空（离线内置清单即生效）。

### 2. 无障碍开屏自动跳过（默认关闭，需用户授权）

- 在系统「无障碍」中启用 **AdSkip** 服务后，App 内的「开屏跳过」页即可开启总开关 `ENABLE_SKIP`。
- 服务监听目标 App（默认 4 大音乐 App：网易云 / QQ音乐 / 酷狗概念版 / 酷我）的开屏/弹窗，**遍历可点击节点**匹配「跳过」按钮文案（正则锚定按钮文案，避免误匹配「关闭会员」），命中后先做**整窗排除词扫描**（确认支付 / 立即支付 / 开通会员 等），任一命中**不点**，防误触；P1 再加 300ms 延迟 + 可见可点击约束后 `performAction(ACTION_CLICK)`。
- 启用 App 列表为空 = 等同关闭（对所有 App 不生效），UI 会提示，不做全局兜底。
- 该线**完全本地**（SharedPreferences，免 root），与 hosts 线解耦、互不影响。

> 双线互补：同一广告可能同时被 hosts 拦请求 + 无障碍点跳过，属预期叠加效果。

---

## 六、兼容性与已知限制

- 屏蔽依赖管理器的 **systemless overlay**（覆盖挂载 `system/`），卸载/禁用模块即恢复原 `hosts`。
- 部分 App 使用**内置 DNS / DoH（DNS-over-HTTPS）** 会绕过系统 `hosts`。模块已默认关闭**系统级 Private DNS**
  （见上「增强说明」）以让系统解析走 `hosts`；但 **App 自建的加密 DNS 通道**仍可能绕过 `hosts`，
  纯 hosts 无法覆盖，需 App 内手动关闭其私有 DNS 或额外做 DoH 拦截。
- **YouTube 等视频广告**通常走与主站同域的 CDN，无法靠 hosts 屏蔽（属已知局限，非本模块缺陷）。
- 少数需要域名解析才能工作的功能（如某些推送/统计 SDK）会被一同屏蔽，属预期行为；如影响正常功能，
  可从 `blocklist.txt` 移除对应域名。

---

## 七、卸载

在 root 管理器中**移除本模块**并重启即可，原 `/system/etc/hosts` 自动恢复，无任何残留。

---

## 八、故障排查

| 现象 | 排查 |
| --- | --- |
| 屏蔽未生效 | 确认模块已启用且已重启；`action.sh status` 查看清单条数；检查 App 是否走 DoH |
| 日志为空 | 确认 `LOG_FILE` 路径可写（`/data/adb/modules/adskip_ksu/` 存在） |
| 在线更新失败 | 检查网络；`UPDATE_URLS` 是否可达；失败会保留旧缓存，不影响基础屏蔽 |
| 想立即生效 | 执行 `action.sh update` 后重启，或在设置中清除 DNS 缓存 |

---

## 九、配套 App（AdSkipManager）

模块内嵌了一款 **全功能 Root 配套 App（`app/AdSkipManager.apk`）**，可在手机上可视化地读写模块状态、开关与日志，无需电脑 / 终端。

### 功能

- **真实读写模块状态**：首页 / 模块页实时显示「是否已激活、Root 管理器（KernelSU / Magisk / APatch）、屏蔽域名总数、上次更新时间、在线更新开关、私有 DNS 关闭开关、模块版本」。数据来自 `action.sh status --json`，绝无写死的演示值。
- **一键开关**：
  - 首页「全局跳广告」主开关、模块页「AdSkip 启用/停用」→ 调用 `action.sh enable` / `disable`。
  - 模块页「关闭系统私有 DNS」「在线更新」开关 → 安全写回 `config.sh`（仅白名单键 + 值格式校验，杜绝注入）。
- **立即更新规则**：应用页「立即更新规则」按钮 → 调用 `action.sh update`。
- **真实日志**：日志页读取 `action.log` 的最近 50 行，并显示真实屏蔽域名数 / 版本。
- **Root 校验**：App 启动即检测 Root；无 Root（或模块未刷入）时，顶部醒目标红提示「本应用需要 Root 权限」，并禁用所有控制按钮。

### 安全与权限

- App 自身 **不声明任何网络权限（无 INTERNET）**：所有模块管理动作都经由 `su` 调用本模块的 `action.sh` 完成（KernelSU / Magisk / APatch 通用），下载等网络行为仍由模块脚本在 root 侧执行，App 只负责触发与展示。
- 桥方法（`runAction` / `setConfig`）均做 **白名单 + 值格式校验**，无法执行任意命令或注入。

### 安装方式

- **随模块自动安装**：刷入模块后，`service.sh` 在开机后期会 best-effort 执行 `pm install -r app/AdSkipManager.apk`（依赖 `pm` 与已授权 Root）。若自动安装被跳过，可手动安装。
- **手动安装**：用文件管理器或 `pm install -r /data/adb/modules/adskip_ksu/app/AdSkipManager.apk` 安装 `app/AdSkipManager.apk`。
- 模块启用/停用、配置修改等改动通常 **重启后完全生效**（与模块约定一致）。

### 技术形态

纯 Java + `su`，零外部库 / Gradle 依赖；WebView 加载本地 `assets/index.html`，通过 `addJavascriptInterface` 暴露 `AdSkipBridge` 桥。构建脚本见 `AdSkip-App/build.sh`（需本地 Android SDK + JDK17）。

---

## 十、文件结构

```
AdSkip-KSU/
├── module.prop              # 模块元信息（三家管理器通用）
├── post-fs-data.sh          # 开机早期：版本守卫 + 生成最终 hosts
├── service.sh               # 开机后期：后台在线更新（节流）+ 自动安装配套 App
├── action.sh                # 手动/按钮：update/enable/disable/status(支持 --json)/rebuild
├── config.sh                # 纯变量配置（被各脚本 source）
├── app/
│   └── AdSkipManager.apk    # 配套 App（随模块自动安装 / 可手动安装）
├── common/
│   ├── lib.sh               # 公共函数（生成 hosts / 下载 / 日志 / 状态 / 状态 JSON）
│   ├── blocklist.txt        # 内置静态域名清单（319 条，v1.1 起保持原契约不变）
│   ├── blocklist_adsdk.txt  # v1.1 广告 SDK 独立域名清单（仅广告域，由 SKIP_ADSDK 门控）
│   ├── adsdk_online.txt     # v1.1(P1) 广告 SDK 在线子清单缓存（运行期生成，可 gitignore）
│   └── downloaded_hosts.txt # 在线清单缓存（自动管理）
├── docs/
│   └── ADSDK_REGRESSION.md  # v1.1 广告 SDK 误伤禁用域 + 回归口径
├── system/
│   └── etc/
│       └── hosts            # 占位兜底（开机由脚本覆写为完整列表）
└── README.md
```

---

## 十一、v1.2 增量说明（缓存修复 · SDK 域名扩充 · VPN 立项）

v1.2 聚焦**修复 v1.1 的卡顿根因**并扩充广告 SDK 域名，同时立项独立 VPN 应用。hosts 线默认全关（`ONLINE_UPDATE=false`、`SKIP_ADSDK=0`），遵循「默认不误伤」。

### 1. 缓存 bug 修复（核心，P0）

**根因**：v1.1 默认 `ONLINE_UPDATE="true"`，5 个激进源合并后 `downloaded_hosts.txt` 高达 265 万条；`generate_hosts` 又**无条件**把该缓存追加进 `/etc/hosts`，导致 DNS 解析拖死。更严重的是，即便把 `ONLINE_UPDATE` 改为 `false`，也只是停了下载，旧缓存仍会被写进 hosts——「关在线更新 ≠ 不卡」。

**修复（设计裁定：Model B — 双重门控）**：

- **默认关在线更新**：`config.sh` 的 `ONLINE_UPDATE` 由 `true` 改为 **`false`**。刷入 v1.2 后，首次生成 hosts 即不再追加任何在线缓存，hosts 回落到内置清单，卡顿消失。
- **双重门控**：`generate_hosts` 追加在线缓存的条件收紧为
  `APPEND ⇔ ( ONLINE_UPDATE=="true" ) AND ( -f common/.dl_online ) AND ( -s common/downloaded_hosts.txt )`
  仅当「在线更新开 + 缓存为在线态（`.dl_online` 标记存在）+ 缓存非空」三者同时满足才追加；否则不追加。缓存态由 `fetch_online` 成功时写入 `.dl_online`、失败时删除来维护。
- **HOSTS 护栏**：`config.sh` 新增 `HOSTS_GUARD_LINES=50000`。`generate_hosts` 去重后若有效行数超过该阈值，记 `warn` 并提示「关闭 ONLINE_UPDATE 或执行 clearcache 以恢复 DNS 性能」。

### 2. 新增 `clearcache` 命令

用于**主动清除在线缓存**并回到离线态：

```sh
sh /data/adb/modules/adskip_ksu/action.sh clearcache
```

语义：截断 `downloaded_hosts.txt` + 删除 `.dl_online` + 删除 `.last_update` + 重新生成 hosts（离线态）。执行后 hosts 仅含内置清单，卡顿必消失。建议在运行 `clearcache` 后重启或清除系统 DNS 缓存。

> 配套 App 会在检测到「陈旧缓存」（`downloaded_hosts.txt` 非空且 `ONLINE_UPDATE!=true` 或无 `.dl_online`）时，首页/模块页黄条提示并提供一键 `clearcache`。`status --json` 新增 `onlineCacheActive` / `cacheLines` / `staleCache` 三个字段供 App 读取。

### 3. `update` 行为变更

- `ONLINE_UPDATE="true"`：与 v1.1 一致，下载 → 标记 `.dl_online` → 重新生成 hosts。
- `ONLINE_UPDATE="false"`（默认）：`update` **只 rebuild、不下载**，避免写入 `.dl_online` 标记，彻底杜绝旧缓存被追加。

### 4. 广告 SDK 域名扩充（P1）

`common/blocklist_adsdk.txt` 在 v1.1 基础上新增多厂商广告 SDK 服务端域（穿山甲扩展、腾讯优量汇、华为、OPPO、vivo、快手磁力、360、多盟、AdView、有米、极光、国际 Google/第三方等），详见 `docs/ADSDK_REGRESSION.md` §五。**仍严格遵循红线**：只收广告投放/竞价/归因 SDK 服务端域，绝不收录任何音乐 App 的播放流/下载/搜索/图片 CDN 或同名内容/主站域（如 `y.qq.com`、`music.huawei.com`、`live.kuaishou.com` 等一律不入）。

### 5. 立项独立 VPN 应用（Phase 2）

v1.2 设计**立项**一款独立 VPN 应用（`com.adskip.vpn`，工程目录 `E:/root模块/AdSkipVPN/`），用于接管设备 DNS、过滤广告域名、屏蔽 DoH 端点，并与真实 VPN 单槽位共存（fake-VPN / DNS-only 分流隧道）。

- **与 manager App 完全分离、不合并**：VPN 应用需 `INTERNET` 权限，而 manager App 坚持「无 INTERNET」安全模型；两者独立 APK、独立工程、独立安装，manager App 仅以 deep-link 方式启动/引导安装 VPN 应用。
- 详细架构见 `docs/system_design_v1.2.md` 第五章与 `E:/root模块/AdSkipVPN/`（MVP 不要求 root）。

### 6. 无障碍开屏跳过增强（P1）

在保留 v1.1「命中『跳过』按钮即点掉」的基础上，v1.2 强化识别与防误触，**全部默认关闭**，需用户在系统无障碍中授权且手动开启：

- **倒计时按钮识别**：新增正则 `跳过\(\d+\)` / `跳过\(\d+\)s?`（如 `跳过(5)` / `跳过(5s)`），可识别带倒计时的跳过按钮文案，不必等待倒计时结束。
- **全屏广告关闭按钮**：新增 `findCloseNode()`，遍历可点击节点识别关闭文案（× / X / 关闭 / close），且当该节点（或其祖先）覆盖屏幕 ≥ 90%（近似全屏遮罩）时判定为全屏广告关闭按钮并点击，覆盖「无『跳过』字样、仅有 X 关闭」的全屏广告场景。
- **上滑 / 滑动关闭（默认关）**：新增 `ENABLE_SLIDE_CLOSE` 开关（**默认 `false`**）。开启后，当命中滑动提示文案（`上滑关闭` / `滑动关闭` / `上滑跳过广告` 等）时，通过 `AccessibilityService.dispatchGesture` 执行水平滑动手势关闭全屏广告（要求 API≥24 且服务已声明 `android:canPerformGestures="true"`，已在 `AndroidManifest.xml` 声明）。该能力默认关闭，避免与点击逻辑冲突或误触。
- **防误触词扩充**：排除词（命中则不点）新增 `了解详情` / `立即体验`；安全点击候选词（不被排除扫描拦截）新增 `知道了` / `稍后`；既有排除词（如 支付 / 开通会员 / 立即支付 等）保持不变。
- 该线仍为纯本地（`SharedPreferences`，免 root），与 hosts 线解耦；配套 App 依然**无 INTERNET 权限**。

> 配套 App 的「开屏跳过」页新增「滑动关闭全屏广告」开关（对应 `ENABLE_SLIDE_CLOSE`，默认关），其余行为同上。
