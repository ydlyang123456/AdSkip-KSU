package com.adskip.app;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JS &lt;-&gt; Native 桥。
 *
 * <p>通过 {@code addJavascriptInterface} 暴露给 WebView 中的 JS，所有方法同步返回字符串，
 * 内部用 {@code su -c} 执行 root 命令（KernelSU / Magisk / APatch 通用）。
 *
 * <p><b>安全约束：</b>
 * <ul>
 *   <li>{@link #runAction(String)} 仅接受白名单命令 {update, rebuild, enable, disable, clearcache}。</li>
 *   <li>{@link #setConfig(String, String)} 仅接受白名单键，且值必须匹配 {@code ^[A-Za-z0-9._:-]+$}，
 *       杜绝 sed 注入。</li>
 *   <li>除白名单外不接受任意命令 / 任意路径。</li>
 * </ul>
 */
@SuppressLint("AddJavascriptInterface")
public class AdSkipBridge {

    /** 宿主 Activity（用于 UI 线程跳转无障碍设置）。 */
    private final MainActivity activity;

    /** 无障碍线本地配置（SharedPreferences）。 */
    private final AdSkipPrefs prefs;

    /** root 检测结果缓存（null 表示尚未检测）。 */
    private Boolean cachedRoot = null;

    /** 宿主 WebView（用于异步桥方法在 UI 线程回调 JS）。 */
    private WebView webView;

    /**
     * 构造桥对象。
     *
     * @param activity 宿主 Activity（AdSkipManager 主界面）
     */
    public AdSkipBridge(MainActivity activity) {
        this.activity = activity;
        this.prefs = new AdSkipPrefs(activity);
    }

    /**
     * 注入宿主 WebView，供异步桥方法（hasRootAsync / getStatusAsync / getConfigAsync）
     * 在后台线程执行完 su 后，切回 UI 线程通过 {@code evaluateJavascript} 回调 JS。
     *
     * <p>必须在 {@code addJavascriptInterface} 之前由 {@link MainActivity} 调用。
     *
     * @param webView 宿主 WebView 实例
     */
    public void setWebView(WebView webView) {
        this.webView = webView;
    }

    /** 模块脚本路径与运行目录（与 AdSkip-KSU 模块保持一致）。 */
    private static final String MODULE_DIR = "/data/adb/modules/adskip_ksu";
    private static final String ACTION_SH = MODULE_DIR + "/action.sh";

    /** root 命令执行结果。 */
    private static final class RootResult {
        final int exitCode;
        final String output;

        RootResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * 以 root 权限执行命令：{@code su -c <cmd>}。
     * 合并 stdout 与 stderr，带 30s 超时保护。
     *
     * @param cmd 要执行的命令（由调用方保证安全）
     * @return 包含 exitCode 与 output 的结果对象
     */
    private RootResult runAsRoot(String cmd) {
        int exitCode = -1;
        String output = "";
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            process = pb.start();

            final InputStream is = process.getInputStream();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (sb.length() > 0) {
                                sb.append('\n');
                            }
                            sb.append(line);
                        }
                    } catch (IOException ignored) {
                        // 流关闭即可，忽略
                    }
                }
            });
            reader.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                try {
                    reader.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new RootResult(-1, "timeout");
            }
            try {
                reader.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exitCode = process.exitValue();
            output = sb.toString();
        } catch (IOException e) {
            output = "io_error";
            exitCode = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output = "interrupted";
            exitCode = -1;
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
        }
        return new RootResult(exitCode, output);
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /** 预热 root 检测（提前弹出 su 授权，结果被缓存供 JS 读取）。 */
    public void warmUpRoot() {
        hasRoot();
    }

    /**
     * 检测设备是否拥有 root（同步核心逻辑，供 {@link #hasRoot()} 与 {@link #hasRootAsync(String)} 复用）。
     *
     * @return 是否拥有 root
     */
    private boolean hasRootSync() {
        if (cachedRoot != null) {
            return cachedRoot;
        }
        RootResult r = runAsRoot("id -u");
        // root 下 id -u 输出 0 且退出码 0
        boolean ok = (r.exitCode == 0) && "0".equals(r.output.trim());
        cachedRoot = ok;
        return ok;
    }

    /**
     * 检测设备是否拥有 root。
     *
     * @return "true" / "false"
     */
    @JavascriptInterface
    public String hasRoot() {
        return hasRootSync() ? "true" : "false";
    }

    /**
     * 读取模块状态（同步核心逻辑，供 {@link #getStatus()} 与 {@link #getStatusAsync(String)} 复用）。
     *
     * @return action.sh 原样输出的 JSON；失败返回 {@code {}}
     */
    private String getStatusSync() {
        RootResult r = runAsRoot("sh " + ACTION_SH + " status --json");
        String base = (r.exitCode == 0 && r.output != null && !r.output.trim().isEmpty())
                ? r.output : "{}";
        // 始终附带 App 自身版本号（动态读取 PackageInfo.versionName），避免界面版本号不随更新刷新
        try {
            String ver = getAppVersion();
            // 仅当模块 JSON 未含 version 字段时注入，互不覆盖
            if (ver != null && !ver.isEmpty() && !base.contains("\"version\"")) {
                base = base.replaceFirst("(\\}\\s*)$", ",\"version\":\"" + ver + "\"$1");
            }
        } catch (Exception ignored) {
        }
        return base;
    }

    /**
     * 读取模块状态（JSON 字符串，由 action.sh status --json 输出）。
     *
     * @return action.sh 原样输出的 JSON；失败返回 {@code {"error":true}}
     */
    @JavascriptInterface
    public String getStatus() {
        return getStatusSync();
    }

    /**
     * 返回本 App 的 versionName（如 1.2.1），供界面显示真实版本。
     */
    @JavascriptInterface
    public String getVersion() {
        String v = getAppVersion();
        return v != null ? v : "";
    }

    private String getAppVersion() {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(activity.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行模块动作（白名单：update / rebuild / enable / disable / clearcache）。
     *
     * @return 动作输出（JSON 或文本）；非法命令返回 {@code {"error":"bad_cmd"}}
     */
    @JavascriptInterface
    public String runAction(String cmd) {
        if (cmd == null) {
            return "{\"error\":\"bad_cmd\"}";
        }
        boolean allowed = "update".equals(cmd) || "rebuild".equals(cmd)
                || "enable".equals(cmd) || "disable".equals(cmd) || "clearcache".equals(cmd);
        if (!allowed) {
            return "{\"error\":\"bad_cmd\"}";
        }
        RootResult r = runAsRoot("sh " + ACTION_SH + " " + cmd);
        if (r.exitCode == 0 && r.output != null && !r.output.trim().isEmpty()) {
            return r.output;
        }
        // 允许动作输出为空（如 enable/disable 仅打日志），统一返回 ok
        return r.exitCode == 0 ? "{\"ok\":true}" : "{\"error\":true}";
    }

    /** 允许写入的白名单配置键。 */
    private static final String[] CONFIG_KEYS = {
            "ONLINE_UPDATE", "DISABLE_PRIVATE_DNS", "REDIRECT_IPV4", "REDIRECT_IPV6",
            "SKIP_ADSDK"
    };

    /**
     * 修改模块配置（白名单键 + 值格式校验，安全写回 config.sh）。
     *
     * @return {@code {"ok":true}} 成功；{@code {"error":"bad_cmd"}} 非法键；
     *         {@code {"error":"bad_value"}} 非法值；{@code {"error":"write_failed"}} 写回失败
     */
    @JavascriptInterface
    public String setConfig(String key, String value) {
        if (key == null || value == null) {
            return "{\"error\":\"bad_value\"}";
        }
        boolean keyOk = false;
        for (String k : CONFIG_KEYS) {
            if (k.equals(key)) {
                keyOk = true;
                break;
            }
        }
        if (!keyOk) {
            return "{\"error\":\"bad_cmd\"}";
        }
        // 值必须匹配白名单字符集，杜绝 sed 注入
        if (!value.matches("^[A-Za-z0-9._:-]+$")) {
            return "{\"error\":\"bad_value\"}";
        }
        String sedCmd = "sed -i 's/^" + key + "=.*/" + key + "=\"" + value + "\"/' "
                + MODULE_DIR + "/config.sh";
        RootResult r = runAsRoot(sedCmd);
        return r.exitCode == 0 ? "{\"ok\":true}" : "{\"error\":\"write_failed\"}";
    }

    /**
     * 读取最近日志（默认最多 50 行）。
     *
     * @param lines 行数（自动钳制到 1..1000）
     * @return 日志文本（失败返回空串）
     */
    @JavascriptInterface
    public String getLog(int lines) {
        if (lines <= 0 || lines > 1000) {
            lines = 50;
        }
        RootResult r = runAsRoot("tail -n " + lines + " " + MODULE_DIR
                + "/action.log 2>/dev/null || echo ''");
        return r.output == null ? "" : r.output;
    }

    // ============================================================
    // v1.1：模块开关联动（hosts 线，走 su / config.sh）
    // ============================================================

    /**
     * 读取模块 config.sh 变量值（同步核心逻辑，供 {@link #getConfig(String)} 与
     * {@link #getConfigAsync(String, String)} 复用）。
     *
     * @param key 白名单键
     * @return {@code {"value":"0"}} 成功；{@code {"error":"bad_cmd"}} 非法键；
     *         {@code {"error":true}} 读取失败
     */
    private String getConfigSync(String key) {
        if (key == null) {
            return "{\"error\":\"bad_cmd\"}";
        }
        boolean ok = false;
        for (String k : CONFIG_KEYS) {
            if (k.equals(key)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            return "{\"error\":\"bad_cmd\"}";
        }
        // 经 AdSkipPrefs 统一走 su 读取 config.sh（内部已做键名白名单校验）
        String val = prefs.getConfigFromShell(key);
        try {
            JSONObject o = new JSONObject();
            o.put("value", val == null ? "" : val);
            return o.toString();
        } catch (JSONException e) {
            return "{\"error\":true}";
        }
    }

    /**
     * 读取模块 config.sh 变量值（白名单键）。供 UI 显示 hosts 开关状态（如 SKIP_ADSDK）。
     *
     * @return {@code {"value":"0"}} 成功；{@code {"error":"bad_cmd"}} 非法键；
     *         {@code {"error":true}} 读取失败
     */
    @JavascriptInterface
    public String getConfig(String key) {
        return getConfigSync(key);
    }

    // ============================================================
    // 异步桥方法（v1.3 修复冷启动黑屏）：后台线程跑 su，结果切回 UI 线程回调 JS
    // ============================================================

    /**
     * 异步检测 root 并把结果回调给 JS（避免冷启动时同步 su 阻塞 JS 引擎导致黑屏）。
     *
     * <p>在后台线程调用 {@link #hasRootSync()}，再经由 {@code webView.evaluateJavascript}
     * 在 UI 线程调用 {@code callback(true|false)}。回调名由 JS 端以字符串传入。
     *
     * @param callback 形如 "window.__onHasRoot" 的全局函数名，回调形如 {@code callback(true)}
     */
    @JavascriptInterface
    public void hasRootAsync(final String callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = hasRootSync();
                final String js = callback + "(" + ok + ")";
                final WebView wv = webView;
                if (wv != null) {
                    wv.post(new Runnable() {
                        @Override
                        public void run() {
                            wv.evaluateJavascript(js, null);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 异步读取模块状态并回调给 JS（避免冷启动 / 刷新时同步 su 阻塞 JS 引擎）。
     *
     * <p>在后台线程调用 {@link #getStatusSync()}，再经由 {@code webView.evaluateJavascript}
     * 在 UI 线程调用 {@code callback('<json>')}（JSON 由 {@link JSONObject#quote} 包裹为字符串字面量）。
     *
     * @param callback 形如 "window.__onStatus" 的全局函数名，回调形如 {@code callback('{"enabled":true}')}
     */
    @JavascriptInterface
    public void getStatusAsync(final String callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String json = getStatusSync();
                final String js = callback + "(" + JSONObject.quote(json) + ")";
                final WebView wv = webView;
                if (wv != null) {
                    wv.post(new Runnable() {
                        @Override
                        public void run() {
                            wv.evaluateJavascript(js, null);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 异步读取模块 config.sh 变量值并回调给 JS（避免同步 su 阻塞 JS 引擎）。
     *
     * <p>在后台线程调用 {@link #getConfigSync(String)}，再经由 {@code webView.evaluateJavascript}
     * 在 UI 线程调用 {@code callback('<json>')}（JSON 由 {@link JSONObject#quote} 包裹为字符串字面量）。
     *
     * @param key 白名单键（如 "SKIP_ADSDK"）
     * @param callback 形如 "window.__onAdsdk" 的全局函数名，回调形如 {@code callback('{"value":"0"}')}
     */
    @JavascriptInterface
    public void getConfigAsync(final String key, final String callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String json = getConfigSync(key);
                final String js = callback + "(" + JSONObject.quote(json) + ")";
                final WebView wv = webView;
                if (wv != null) {
                    wv.post(new Runnable() {
                        @Override
                        public void run() {
                            wv.evaluateJavascript(js, null);
                        }
                    });
                }
            }
        }).start();
    }

    // ============================================================
    // v1.2：在线缓存管理（本地调用 action.sh clearcache / status，无 su 注入、无 INTERNET）
    // ============================================================

    /**
     * 清除在线缓存（白名单命令 clearcache）：截断 downloaded_hosts.txt + 删 .dl_online + 删 .last_update + rebuild。
     *
     * @return {@code {"ok":true}} / {@code {"error":true}}
     */
    @JavascriptInterface
    public String clearCache() {
        RootResult r = runAsRoot("sh " + ACTION_SH + " clearcache");
        return r.exitCode == 0 ? "{\"ok\":true}" : "{\"error\":true}";
    }

    /**
     * 读取在线缓存态（来自 action.sh status --json 的 v1.2 新增字段）。
     *
     * @return JSON：{@code onlineCacheFresh}(对应 status 的 onlineCacheActive) /
     *         {@code cachedLines}(对应 status 的 cacheLines) /
     *         {@code staleCache}(对应 status 的 staleCache，缓存非空且非在线态)
     */
    @JavascriptInterface
    public String getCacheState() {
        try {
            RootResult r = runAsRoot("sh " + ACTION_SH + " status --json");
            JSONObject j = new JSONObject(r.output);
            if (j.has("error")) {
                return "{\"onlineCacheFresh\":false,\"cachedLines\":0,\"staleCache\":false}";
            }
            // 字段名与 common/lib.sh print_status_json 保持一致：
            //   onlineCacheActive（在线更新开 + 在线态标记 + 缓存非空）↔ onlineCacheFresh
            //   cacheLines（downloaded_hosts.txt 有效行数）↔ cachedLines
            //   staleCache（缓存非空 且 非在线态）→ 陈旧缓存，提示清理
            boolean fresh = j.optBoolean("onlineCacheActive", false);
            int lines = j.optInt("cacheLines", 0);
            boolean stale = j.optBoolean("staleCache", (lines > 0) && !fresh);
            JSONObject o = new JSONObject();
            o.put("onlineCacheFresh", fresh);
            o.put("cachedLines", lines);
            o.put("staleCache", stale);
            return o.toString();
        } catch (Exception e) {
            return "{\"onlineCacheFresh\":false,\"cachedLines\":0,\"staleCache\":false}";
        }
    }

    // ============================================================
    // v1.2：AdSkip VPN 入口（deep-link，仅查询包/启动 Activity，无 INTERNET、不内嵌 VPN 逻辑）
    // ============================================================

    /** v1.2 VPN 应用包名（独立工程，与管理者 App 完全分离）。 */
    public static final String VPN_PACKAGE = "com.adskip.vpn";

    /**
     * 查询 AdSkip VPN 是否已安装。
     *
     * @return {@code {"installed":true|false}}
     */
    @JavascriptInterface
    public String getVpnInfo() {
        try {
            PackageManager pm = activity.getPackageManager();
            boolean installed;
            try {
                pm.getPackageInfo(VPN_PACKAGE, 0);
                installed = true;
            } catch (Exception e) {
                installed = false;
            }
            JSONObject o = new JSONObject();
            o.put("installed", installed);
            return o.toString();
        } catch (Exception e) {
            return "{\"installed\":false}";
        }
    }

    /**
     * deep-link 启动 AdSkip VPN（已安装则启动；未安装返回 installed:false）。
     * 仅在 UI 线程发起 Activity，避免跨线程启动异常。
     *
     * @return {@code {"installed":true}} 已启动 / {@code {"installed":false}} 未安装 / {@code {"error":true}}
     */
    @JavascriptInterface
    public String openVpnApp() {
        if (activity == null) {
            return "{\"error\":true}";
        }
        final boolean[] installed = {false};
        try {
            PackageManager pm = activity.getPackageManager();
            try {
                pm.getPackageInfo(VPN_PACKAGE, 0);
                installed[0] = true;
            } catch (Exception e) {
                installed[0] = false;
            }
        } catch (Exception e) {
            return "{\"error\":true}";
        }
        if (!installed[0]) {
            return "{\"installed\":false}";
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    i.setPackage(VPN_PACKAGE);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception ignored) {
                    // 启动失败（如被禁用）忽略，UI 已据 installed 提示
                }
            }
        });
        return "{\"installed\":true}";
    }

    // ============================================================
    // v1.1：无障碍开屏跳过（本地 SharedPreferences，免 root，无命令执行）
    // ============================================================

    /**
     * 检测本无障碍服务是否已在系统设置中启用。
     *
     * @return "true" / "false"
     */
    @JavascriptInterface
    public String isAccessibilityEnabled() {
        try {
            ContentResolver cr = activity.getContentResolver();
            String enabled = Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null && enabled.contains(
                    "com.adskip.app/com.adskip.app.AdSkipAccessibilityService")) {
                return "true";
            }
        } catch (Exception e) {
            // 读取失败视为未启用
        }
        return "false";
    }

    /**
     * UI 线程跳转到系统无障碍设置页（供用户授权）。
     *
     * @return "ok"
     */
    @JavascriptInterface
    public String openAccessibilitySettings() {
        if (activity == null) {
            return "error";
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception e) {
                    // 跳转失败忽略
                }
            }
        });
        return "ok";
    }

    /**
     * 一次性返回全部无障碍配置 + 权限状态（供「开屏跳过」页渲染）。
     *
     * @return JSON（见设计文档 §3.4 示例）
     */
    @JavascriptInterface
    public String getSkipConfig() {
        try {
            boolean permission = "true".equals(isAccessibilityEnabled());
            boolean enable = prefs.isSkipEnabled();
            Set<String> apps = prefs.getEnabledApps();
            Set<String> skipKw = prefs.getSkipKeywords();
            Set<String> exKw = prefs.getExcludeKeywords();

            JSONObject o = new JSONObject();
            o.put("permission", permission);
            o.put("enable", enable);
            o.put("enabledApps", new JSONArray(apps));
            o.put("enabledAppCount", apps.size());
            o.put("skipKeywords", new JSONArray(skipKw));
            o.put("excludeKeywords", new JSONArray(exKw));
            o.put("wifiOnly", prefs.isWifiOnly());
            o.put("skipDelayMs", prefs.getSkipDelayMs());
            o.put("subwindowExclude", prefs.isSubwindowExclude());
            o.put("slideClose", prefs.isSlideCloseEnabled());
            o.put("todayCount", prefs.getTodayCount());
            return o.toString();
        } catch (JSONException e) {
            return "{\"error\":true}";
        }
    }

    /**
     * 写 ENABLE_SKIP（总开关）。开启时（API>=31）尝试启动前台保活；关闭时停止。
     *
     * @return {@code {"ok":true}}
     */
    @JavascriptInterface
    public String setSkipEnable(String v) {
        if (v == null) {
            return "{\"error\":\"bad_value\"}";
        }
        boolean on = "true".equals(v) || "1".equals(v);
        prefs.putBool(AdSkipPrefs.KEY_ENABLE_SKIP, on);
        if (on) {
            startKeepAliveIfNeeded();
        } else {
            stopKeepAliveIfNeeded();
        }
        return "{\"ok\":true}";
    }

    /**
     * 覆盖写 ENABLED_APPS（启用 App 包名集合）。
     *
     * @param json JSON 数组字符串，如 ["com.netease.cloudmusic",...]
     * @return {@code {"ok":true}} / {@code {"error":"bad_value"}}
     */
    @JavascriptInterface
    public String setEnabledApps(String json) {
        if (json == null) {
            return "{\"error\":\"bad_value\"}";
        }
        try {
            JSONArray arr = new JSONArray(json);
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i);
                if (s != null && !s.isEmpty()) {
                    set.add(s);
                }
            }
            prefs.putStringSet(AdSkipPrefs.KEY_ENABLED_APPS, set);
            return "{\"ok\":true}";
        } catch (JSONException e) {
            return "{\"error\":\"bad_value\"}";
        }
    }

    /**
     * 覆盖写 SKIP_KEYWORDS（跳过按钮文案正则片段）。
     *
     * @param json JSON 数组字符串
     * @return {@code {"ok":true}} / {@code {"error":"bad_value"}}
     */
    @JavascriptInterface
    public String setSkipKeywords(String json) {
        if (json == null) {
            return "{\"error\":\"bad_value\"}";
        }
        try {
            JSONArray arr = new JSONArray(json);
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i);
                if (s != null && !s.isEmpty()) {
                    set.add(s);
                }
            }
            prefs.putStringSet(AdSkipPrefs.KEY_SKIP_KEYWORDS, set);
            return "{\"ok\":true}";
        } catch (JSONException e) {
            return "{\"error\":\"bad_value\"}";
        }
    }

    /**
     * 覆盖写 EXCLUDE_KEYWORDS（防误触排除词）。
     *
     * @param json JSON 数组字符串
     * @return {@code {"ok":true}} / {@code {"error":"bad_value"}}
     */
    @JavascriptInterface
    public String setExcludeKeywords(String json) {
        if (json == null) {
            return "{\"error\":\"bad_value\"}";
        }
        try {
            JSONArray arr = new JSONArray(json);
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i);
                if (s != null && !s.isEmpty()) {
                    set.add(s);
                }
            }
            prefs.putStringSet(AdSkipPrefs.KEY_EXCLUDE_KEYWORDS, set);
            return "{\"ok\":true}";
        } catch (JSONException e) {
            return "{\"error\":\"bad_value\"}";
        }
    }

    /**
     * 写 WIFI_ONLY（P2 仅 WiFi 下跳过）。
     *
     * @return {@code {"ok":true}}
     */
    @JavascriptInterface
    public String setWifiOnly(String v) {
        if (v == null) {
            return "{\"error\":\"bad_value\"}";
        }
        boolean on = "true".equals(v) || "1".equals(v);
        prefs.putBool(AdSkipPrefs.KEY_WIFI_ONLY, on);
        return "{\"ok\":true}";
    }

    /**
     * 写 ENABLE_SLIDE_CLOSE（v1.2 滑动关闭全屏广告，默认关）。
     *
     * @return {@code {"ok":true}}
     */
    @JavascriptInterface
    public String setSlideClose(String v) {
        if (v == null) {
            return "{\"error\":\"bad_value\"}";
        }
        boolean on = "true".equals(v) || "1".equals(v);
        prefs.putBool(AdSkipPrefs.KEY_ENABLE_SLIDE_CLOSE, on);
        return "{\"ok\":true}";
    }

    /**
     * 读取今日跳过次数。
     *
     * @return {@code {"date":"...","count":0}}
     */
    @JavascriptInterface
    public String getSkipStats() {
        try {
            JSONObject o = new JSONObject();
            o.put("date", prefs.getString(AdSkipPrefs.KEY_STATS_DATE, ""));
            o.put("count", prefs.getTodayCount());
            return o.toString();
        } catch (JSONException e) {
            return "{\"error\":true}";
        }
    }

    // ---- 保活辅助（P1） ----
    private void startKeepAliveIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && activity != null) {
            try {
                Intent i = new Intent(activity, AdSkipKeepAliveService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    activity.startForegroundService(i);
                } else {
                    activity.startService(i);
                }
            } catch (Exception ignored) {
                // 保活为兜底，失败不影响跳过逻辑
            }
        }
    }

    private void stopKeepAliveIfNeeded() {
        if (activity != null) {
            try {
                activity.stopService(new Intent(activity, AdSkipKeepAliveService.class));
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }
}
