package com.adskip.vpn;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 主界面：启停 VPN 总开关、实时拦截统计、按 App 放行/拦截多选、DoH 开关、
 * 单槽位 VPN 冲突提示、可选「从模块导入 blocklist」（root）。
 */
public final class MainActivity extends Activity {
    private static final int REQ_PREPARE = 1;

    private Switch swEnable;
    private Switch swDoh;
    private TextView tvBlocked;
    private TextView tvConflict;
    private TextView tvStatus;
    private ListView listApps;
    private Button btnImport;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            tvBlocked.setText(getString(R.string.blocked_count, BlockStats.getTotal(MainActivity.this)));
            uiHandler.postDelayed(this, 1000);
        }
    };

    private AppAdapter appAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swEnable = findViewById(R.id.sw_enable);
        swDoh = findViewById(R.id.sw_doh);
        tvBlocked = findViewById(R.id.tv_blocked);
        tvConflict = findViewById(R.id.tv_conflict);
        tvStatus = findViewById(R.id.tv_status);
        listApps = findViewById(R.id.list_apps);
        btnImport = findViewById(R.id.btn_import);

        swEnable.setChecked(AdSkipVpnService.isRunning());
        swDoh.setChecked(Prefs.isDohEnabled(this));

        swEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (isChecked) {
                    startVpn();
                } else {
                    stopVpn();
                }
            }
        });
        swDoh.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                Prefs.setDohEnabled(MainActivity.this, isChecked);
            }
        });
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFromModule();
            }
        });

        loadAppList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        swEnable.setChecked(AdSkipVpnService.isRunning());
        updateConflict();
        tvBlocked.setText(getString(R.string.blocked_count, BlockStats.getTotal(this)));
        String status = AdSkipVpnService.isRunning()
                ? getString(R.string.vpn_notif_title) : getString(R.string.status_idle);
        tvStatus.setText(status);
        uiHandler.postDelayed(refreshTask, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(refreshTask);
    }

    private void updateConflict() {
        boolean other = isAnotherVpnActive();
        tvConflict.setVisibility(other ? View.VISIBLE : View.GONE);
    }

    private void startVpn() {
        updateConflict();
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            try {
                startActivityForResult(intent, REQ_PREPARE);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "无法启动 VPN 授权界面", Toast.LENGTH_LONG).show();
                swEnable.setChecked(false);
            }
        } else {
            Prefs.setEnabled(this, true);
            startService(new Intent(this, AdSkipVpnService.class));
        }
    }

    private void stopVpn() {
        Prefs.setEnabled(this, false);
        stopService(new Intent(this, AdSkipVpnService.class));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PREPARE) {
            if (resultCode == RESULT_OK) {
                Prefs.setEnabled(this, true);
                startService(new Intent(this, AdSkipVpnService.class));
            } else {
                swEnable.setChecked(false);
            }
        }
    }

    /** 检测是否有其他 VPN 正在运行（单槽位硬限制）。 */
    private boolean isAnotherVpnActive() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network net = cm.getActiveNetwork();
        if (net == null) {
            return false;
        }
        NetworkCapabilities cap = cm.getNetworkCapabilities(net);
        if (cap == null) {
            return false;
        }
        // 活跃网络若不是「非 VPN」网络，则说明有 VPN 正在运行
        return !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
    }

    // ---------- 按 App 列表 ----------
    private static final class AppItem {
        final String pkg;
        final String label;

        AppItem(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void loadAppList() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<AppItem> items = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(getPackageName())) {
                continue; // 跳过自身
            }
            String label = pm.getApplicationLabel(ai).toString();
            items.add(new AppItem(ai.packageName, label));
        }
        Collections.sort(items, new java.util.Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        appAdapter = new AppAdapter(this, items);
        listApps.setAdapter(appAdapter);
    }

    private final class AppAdapter extends ArrayAdapter<AppItem> {
        private final Set<String> allowed;

        AppAdapter(Context ctx, List<AppItem> items) {
            super(ctx, R.layout.app_row, R.id.tv_app, items);
            allowed = AppPolicy.getAllowedApps(ctx);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            final AppItem item = getItem(position);
            TextView tv = v.findViewById(R.id.tv_app);
            CheckBox cb = v.findViewById(R.id.cb_app);
            tv.setText(item.label + "\n" + item.pkg);
            cb.setChecked(allowed.contains(item.pkg));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                    if (item == null) {
                        return;
                    }
                    AppPolicy.setAllowed(getContext(), item.pkg, isChecked);
                    if (isChecked) {
                        allowed.add(item.pkg);
                    } else {
                        allowed.remove(item.pkg);
                    }
                    // 策略变更需重启 VPN 才能生效
                    if (AdSkipVpnService.isRunning()) {
                        stopService(new Intent(getContext(), AdSkipVpnService.class));
                        Prefs.setEnabled(getContext(), true);
                        startService(new Intent(getContext(), AdSkipVpnService.class));
                    }
                }
            });
            return v;
        }
    }

    // ---------- 从模块导入（root 可选，T9） ----------
    private void importFromModule() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = runImport();
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (n < 0) {
                            Toast.makeText(MainActivity.this,
                                    "导入失败：需 root 授权，或模块未安装", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "已从 AdSkip 模块导入 " + n + " 条域名", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private int runImport() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cat /data/adb/modules/adskip_ksu/common/blocklist.txt "
                            + "/data/adb/modules/adskip_ksu/common/blocklist_adsdk.txt 2>/dev/null"});
            InputStream in = p.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            int added = 0;
            String line;
            while ((line = br.readLine()) != null) {
                String d = line.trim().toLowerCase();
                if (d.isEmpty() || d.startsWith("#")) {
                    continue;
                }
                int h = d.indexOf('#');
                if (h >= 0) {
                    d = d.substring(0, h).trim();
                }
                if (d.endsWith(".")) {
                    d = d.substring(0, d.length() - 1);
                }
                if (d.isEmpty()) {
                    continue;
                }
                Blocklist.addDomain(d);
                added++;
            }
            br.close();
            p.waitFor();
            return added;
        } catch (Exception e) {
            return -1;
        }
    }
}
