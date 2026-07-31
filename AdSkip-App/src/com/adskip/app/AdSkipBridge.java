package com.adskip.app;

import android.annotation.SuppressLint;
import android.webkit.JavascriptInterface;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * JS &lt;-&gt; Native 桥。
 *
 * <p>通过 {@code addJavascriptInterface} 暴露给 WebView 中的 JS，所有方法同步返回字符串，
 * 内部用 {@code su -c} 执行 root 命令（KernelSU / Magisk / APatch 通用）。
 *
 * <p><b>安全约束：</b>
 * <ul>
 *   <li>{@link #runAction(String)} 仅接受白名单命令 {update, rebuild, enable, disable}。</li>
 *   <li>{@link #setConfig(String, String)} 仅接受白名单键，且值必须匹配 {@code ^[A-Za-z0-9._:-]+$}，
 *       杜绝 sed 注入。</li>
 *   <li>除白名单外不接受任意命令 / 任意路径。</li>
 * </ul>
 */
@SuppressLint("AddJavascriptInterface")
public class AdSkipBridge {

    /** 宿主 Activity（用于未来扩展，如 UI 回调）。 */
    private final MainActivity activity;

    /** root 检测结果缓存（null 表示尚未检测）。 */
    private Boolean cachedRoot = null;

    /**
     * 构造桥对象。
     *
     * @param activity 宿主 Activity（AdSkipManager 主界面）
     */
    public AdSkipBridge(MainActivity activity) {
        this.activity = activity;
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
     * 检测设备是否拥有 root。
     *
     * @return "true" / "false"
     */
    @JavascriptInterface
    public String hasRoot() {
        if (cachedRoot != null) {
            return cachedRoot ? "true" : "false";
        }
        RootResult r = runAsRoot("id -u");
        // root 下 id -u 输出 0 且退出码 0
        boolean ok = (r.exitCode == 0) && "0".equals(r.output.trim());
        cachedRoot = ok;
        return ok ? "true" : "false";
    }

    /**
     * 读取模块状态（JSON 字符串，由 action.sh status --json 输出）。
     *
     * @return action.sh 原样输出的 JSON；失败返回 {@code {"error":true}}
     */
    @JavascriptInterface
    public String getStatus() {
        RootResult r = runAsRoot("sh " + ACTION_SH + " status --json");
        if (r.exitCode == 0 && r.output != null && !r.output.trim().isEmpty()) {
            return r.output;
        }
        return "{\"error\":true}";
    }

    /**
     * 执行模块动作（白名单：update / rebuild / enable / disable）。
     *
     * @return 动作输出（JSON 或文本）；非法命令返回 {@code {"error":"bad_cmd"}}
     */
    @JavascriptInterface
    public String runAction(String cmd) {
        if (cmd == null) {
            return "{\"error\":\"bad_cmd\"}";
        }
        boolean allowed = "update".equals(cmd) || "rebuild".equals(cmd)
                || "enable".equals(cmd) || "disable".equals(cmd);
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
            "ONLINE_UPDATE", "DISABLE_PRIVATE_DNS", "REDIRECT_IPV4", "REDIRECT_IPV6"
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
}
