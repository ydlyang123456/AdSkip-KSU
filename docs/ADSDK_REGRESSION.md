# AdSkip-KSU v1.1 · 广告 SDK 域名误伤禁用清单与回归口径

> 配套：`common/blocklist_adsdk.txt`（开关 `SKIP_ADSDK`，默认关闭，rebuild 生效）
> 目标：**只拦广告投放/竞价域，绝不误伤播放/内容/CDN 域**。

---

## 一、判定原则（按「域用途」而非「关键词」）

| 域类型 | 是否入 `blocklist_adsdk.txt` | 说明 |
| --- | --- | --- |
| 广告投放/竞价域（Ad SDK 服务端） | ✅ 允许 | 例：`pangle.io`、`gdt.qq.com`、`ksads.kuaishou.com`、`mobads.baidu.com`、`tanx.com`、`api.applovin.com` |
| 同名内容域/主站域 | ❌ 禁止 | 例：`music.163.com`、`y.qq.com`、`www.kugou.com`、`www.kuwo.cn` |
| 音乐播放/下载流媒体域 | ❌ 禁止 | 任何音频流、MV 流、歌词/封面 CDN |
| 账号/支付/社交域 | ❌ 禁止 | 登录、支付、IM、分享回调用域 |
| 统计/推送（非广告） | ⚠️ 谨慎 | 仅当确认用于广告归因且不影响播放功能时才考虑，默认不入 |

---

## 二、禁用域清单（误伤红线，禁止写入）

> 以下为**代表性**红线域（非穷举）。任何疑似同名内容/播放/CDN 域，先按 §一 判定，拿不准一律不入。

- 网易云：`music.163.com`、`*.music.163.com`、`ipfs.stream.163.com`、`np.music.163.com`
- QQ 音乐：`y.qq.com`、`*.y.qq.com`、`stream.music.qq.com`、`c.y.qq.com`
- 酷狗：`gdispatcher.kugou.com`、`*.kugou.com`（内容/播放）、`stream.kugou.com`
- 酷我：`*.kuwo.cn`（内容/播放）、`newlyric.kuwo.cn`
- 通用音乐 CDN：`*.qbox.me`、`*.uptocdn.com`、`*.musiccdn.*` 等音频/图片 CDN

---

## 三、回归口径（真机验证，沙箱无外网无法替代）

1. **默认关验证**：`SKIP_ADSDK=0`（默认）时，`sh action.sh rebuild` 后 `system/etc/hosts` **不含** 任何 `blocklist_adsdk.txt` 中的域（grep 校验）。
2. **增量验证**：置 `SKIP_ADSDK=1` 后 rebuild，`hosts` 仅在默认 319 条基础上**增量**新增 adsdk 域，不删除/不改写既有条目。
3. **播放功能回归**（4 大音乐 App 真机）：
   - 网易云音乐 / QQ音乐 / 酷狗概念版 / 酷我音乐：播放歌曲、暂停/续播、搜索、下载、查看歌词/封面均正常。
   - 开屏广告被跳过（开启无障碍线时）；关闭 `SKIP_ADSDK` 后播放不受影响（hosts 线独立）。
4. **误伤豁免**：若某 App 因 adsdk 域被误伤（如某播放功能恰走广告域），处置优先级：
   1. 从 `blocklist_adsdk.txt` 移除该域（最快）；
   2. 或直接 `SKIP_ADSDK=0`（整条 hosts 线关闭，不影响无障碍线）。
   - 模块不提供「逐域白名单」机制（保持简洁），误伤通过删域/关开关解决。

---

## 四、与无障碍线的边界

- 本清单只管 **hosts 线**（网络层屏蔽广告请求）。
- **开屏/弹窗/信息流广告的「点击跳过」由无障碍线（`ENABLE_SKIP`）负责**，两者互补、互不依赖。
- 同一广告可能同时被两条线处理（hosts 拦请求 + 无障碍点跳过），属预期叠加效果。

---

## 五、v1.2 扩充域回归口径

> 本节记录 v1.2 在 `blocklist_adsdk.txt` 中**新增**的广告 SDK 域，并复核「仅广告域」判定与误伤红线。

### 5.1 本次新增域清单（按分类）

| 分类 | 新增域 | 为何仅属广告域 |
| --- | --- | --- |
| 穿山甲扩展 | `log.pangle.io`、`agent.pangle.io` | 穿山甲（Pangle）SDK 日志/监控上报与 agent 服务端域，无任何音乐内容/播放流 |
| 腾讯优量汇 | `ylh.qq.com` | 腾讯优量汇（YLH）广告交易服务端域；**注意区分 `y.qq.com`（QQ音乐内容，禁）** |
| 华为广告 | `ads.huawei.com` | 华为广告服务端域；**区分 `music.huawei.com`（华为音乐内容，禁）** |
| OPPO | `ads.coloros.com` | ColorOS 广告服务端域 |
| vivo | `ads.vivo.com` | vivo 广告服务端域 |
| 快手磁力 | `ad.kuaishou.com` | 快手磁力（Magnet）广告投放域；**区分 `live.kuaishou.com`（快手直播内容，禁）** |
| 360 | `ad.360.cn`、`ads.360.cn` | 360 广告服务端域 |
| 多盟 | `domob.cn`、`s.domob.cn` | 多盟（Domob）广告交易平台域 |
| AdView | `adview.cn`、`a.adview.cn` | AdView 广告 SDK 服务端域 |
| 有米 | `ad.youmi.net`、`youmi.net` | 有米（Youmi）广告 SDK 服务端域（非音乐 App） |
| 极光 | `ad.jiguang.cn` | 极光（Jiguang）推送/广告服务端域 |
| 国际 | `googleads.g.doubleclick.net`、`pagead2.googlesyndication.com`、`adservice.google.com`、`admob.com`、`criteo.com`、`pubmatic.com` | Google/第三方国际广告投放与竞价服务端域 |

### 5.2 判定复核（只收广告/SDK 服务端域）

- 上述域名**全部**对应广告投放/竞价/归因 SDK 的服务端，不含任何音频流、MV 流、歌词/封面图片 CDN。
- 凡与「同名内容域/主站域」易混淆者（如 `ylh.qq.com` vs `y.qq.com`、`ads.huawei.com` vs `music.huawei.com`、`ad.kuaishou.com` vs `live.kuaishou.com`），均已逐一核对：仅收广告侧，内容侧一律不入。

### 5.3 谨慎域（默认不收，留待后续开关）

- **推送/归因类谨慎域**：`getui.com` / `sdk.getui.com` 等个推、`jpush.cn` 等极光主推送域，可能承载非广告业务（推送/IM），**本次默认不收入** `blocklist_adsdk.txt`，避免误伤正常推送；后续如需纳入，应单独做成可开关选项，默认关闭。
- 若后续确认某推送/归因域**确仅用于广告归因且不影响播放功能**，再按 §一 谨慎评估纳入。

### 5.4 误伤即移除机制

- 若真机发现某 App 因 v1.2 新增域被误伤：
  1. 直接从 `blocklist_adsdk.txt` 移除该域（最快，下次 `rebuild` 即刻恢复）；
  2. 或置 `SKIP_ADSDK=0`（整条 hosts 线关闭，播放域不受任何影响）。
- 模块不提供「逐域白名单」，保持简洁；误伤处置以「删域 / 关开关」为唯一路径。
- v1.2 新增域均需在真机回归中复测 4 大音乐 App（网易云 / QQ音乐 / 酷狗概念版 / 酷我）播放、搜索、下载、歌词/封面不受影响。
