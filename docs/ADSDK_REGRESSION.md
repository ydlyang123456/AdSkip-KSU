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
