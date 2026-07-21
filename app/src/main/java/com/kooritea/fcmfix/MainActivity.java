package com.kooritea.fcmfix;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.kooritea.fcmfix.util.IceboxUtils;

public class MainActivity extends AppCompatActivity {
    private AppListAdapter appListAdapter;
    private RecyclerView recyclerView;
    private static XposedService xposedService;
    Set<String> allowList = new HashSet<>();
    JSONObject config = new JSONObject();
    // 本地配置：UI 的可靠来源，不依赖 LSPosed 远程通道（Android 16 上 XposedService 连接不稳）
    private SharedPreferences localPref;

    private SharedPreferences getRemotePreferencesOrNull() {
        if (xposedService == null) {
            return null;
        }
        try {
            return xposedService.getRemotePreferences("config");
        } catch (Throwable e) {
            Log.e("getRemotePreferences", e.toString());
            return null;
        }
    }

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    xposedService = service;
                    runOnUiThread(() -> flushToRemote());
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                }
            });
        } catch (Throwable e) {
            Log.e("initXposedService", e.toString());
        }
    }

    private void initLocalPref() {
        localPref = getSharedPreferences("config", MODE_PRIVATE);
    }

    private void ensureDefaultConfigValues() {
        try {
            if (!this.config.has("allowList")) {
                this.config.put("allowList", new JSONArray());
            }
            if (!this.config.has("disableAutoCleanNotification")) {
                this.config.put("disableAutoCleanNotification", false);
            }
            if (!this.config.has("includeIceBoxDisableApp")) {
                this.config.put("includeIceBoxDisableApp", false);
            }
            if (!this.config.has("noResponseNotification")) {
                this.config.put("noResponseNotification", false);
            }
        } catch (JSONException e) {
            Log.e("ensureDefaultConfig", e.toString());
        }
    }

    private void loadConfig() {
        this.allowList.clear();
        this.allowList.addAll(localPref.getStringSet("allowList", new HashSet<>()));
        try {
            this.config.put("allowList", new JSONArray(this.allowList));
            this.config.put("disableAutoCleanNotification", localPref.getBoolean("disableAutoCleanNotification", false));
            this.config.put("includeIceBoxDisableApp", localPref.getBoolean("includeIceBoxDisableApp", false));
            this.config.put("noResponseNotification", localPref.getBoolean("noResponseNotification", false));
        } catch (JSONException e) {
            Log.e("loadConfig", e.toString());
        }
        ensureDefaultConfigValues();
    }

    private class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public Boolean isAllow = false;
        public Boolean includeFcm = false;

        public AppInfo(PackageInfo packageInfo) {
            this.name = packageInfo.applicationInfo.loadLabel(getPackageManager()).toString();
            this.packageName = packageInfo.packageName;
            this.icon = packageInfo.applicationInfo.loadIcon(getPackageManager());
        }
    }

    private class AppListAdapter  extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        private final List<AppInfo> mAppList;
        class ViewHolder extends RecyclerView.ViewHolder {
            View appView;
            ImageView icon;
            TextView name;
            TextView packageName;
            TextView includeFcm;
            CheckBox isAllow;

            public ViewHolder(View view) {
                super(view);
                appView = view;
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                packageName = view.findViewById(R.id.packageName);
                includeFcm = view.findViewById(R.id.includeFcm);
                isAllow = view.findViewById(R.id.isAllow);
            }
        }

        public AppListAdapter(){
            Set<String> allowListSet = new HashSet<>(allowList);
            List<AppInfo> _allowList = new ArrayList<>();
            List<AppInfo> _notAllowList = new ArrayList<>();
            List<AppInfo> _notFoundFcm = new ArrayList<>();
            PackageManager packageManager = getPackageManager();
            for(PackageInfo packageInfo : packageManager.getInstalledPackages(PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                boolean flag = false;
                AppInfo appInfo = new AppInfo(packageInfo);
                if (packageInfo.receivers != null) {
                    for (ActivityInfo  receiverInfo : packageInfo.receivers ){
                        if(receiverInfo.name.equals("com.google.firebase.iid.FirebaseInstanceIdReceiver") || receiverInfo.name.equals("com.google.android.gms.measurement.AppMeasurementReceiver")){
                            flag = true;
                            appInfo.includeFcm = true;
                            break;
                        }
                    }
                }else{
                    continue;
                }
                if(allowListSet.contains(appInfo.packageName)){
                    appInfo.isAllow = true;
                    _allowList.add(appInfo);
                }else{
                    if(flag){
                        _notAllowList.add(appInfo);
                    }else{
                        _notFoundFcm.add(appInfo);
                    }
                }
            }
            class SortName implements Comparator<AppInfo> {
                final Collator localCompare = Collator.getInstance(Locale.getDefault());
                @Override
                public int compare(AppInfo a1, AppInfo a2) {
                    if(localCompare.compare(a1.name,a2.name)>0){
                        return 1;
                    }else if (localCompare.compare(a1.name, a2.name) < 0) {
                        return -1;
                    }
                    return 0;
                }
            }
            final SortName sortName = new SortName();
            _allowList.sort(sortName);
            _notAllowList.sort(sortName);
            _notFoundFcm.sort(sortName);
            _allowList.addAll(_notAllowList);
            _allowList.addAll(_notFoundFcm);
            this.mAppList = _allowList;
            if(_allowList.size() == 0 || _allowList.isEmpty() ||(_allowList.size() == 1 && "com.kooritea.fcmfix".equals(_allowList.get(0).packageName))){
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("请在系统设置中授予读取应用列表权限")
                        .setMessage("请确认 LSPosed 框架已激活，且本模块作用域已勾选「系统框架」；配置通过 LSPosed 远程 Preferences 存储。")
                        .setPositiveButton("确定", (dialog, which) -> {})
                        .show();
            }
        }


        @SuppressLint("NotifyDataSetChanged")
        @NonNull
        @Override
        public AppListAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.app_item, parent, false);
            final ViewHolder holder = new ViewHolder(view);
            holder.appView.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                AppInfo appInfo = mAppList.get(position);
                boolean willAllow = !allowList.contains(appInfo.packageName);
                if (willAllow) {
                    addAppInAllowList(appInfo.packageName);
                } else {
                    deleteAppInAllowList(appInfo.packageName);
                }
                // 勾选状态由实时 allowList 驱动（见 onBindViewHolder），写入失败时不会误显示
                appListAdapter.notifyDataSetChanged();
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull AppListAdapter.ViewHolder holder, int position) {
            AppInfo appInfo = mAppList.get(position);
            holder.icon.setImageDrawable(appInfo.icon);
            holder.name.setText(appInfo.name);
            holder.packageName.setText(appInfo.packageName);
            holder.includeFcm.setVisibility(appInfo.includeFcm ? View.VISIBLE : View.GONE);
            holder.isAllow.setChecked(allowList.contains(appInfo.packageName));
        }

        @Override
        public int getItemCount() {
            return mAppList.size();
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        initXposedService();
        initLocalPref();
        ensureDefaultConfigValues();

        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }

        // 本地配置始终可用，1s 后用本地配置构建列表（不依赖 LSPosed 远程通道）
        new Handler().postDelayed(() -> {
            if (appListAdapter == null) {
                refreshList();
            }
        }, 1000);
    }

    @Nullable
    @Override
    public View onCreateView(@Nullable View parent, @NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
        return super.onCreateView(parent, name, context, attrs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台时若列表尚未构建，用本地配置补加载（不依赖远程通道，修复重建后勾选不显示）
        if (appListAdapter == null) {
            refreshList();
        }
    }

    /**
     * 统一入口：加载远程配置 + 应用绑定前待落盘变更 + 重建列表。
     * 在 onServiceBind / postDelayed 兜底 / onResume 兜底 三处调用，
     * 确保无论服务何时连上、Activity 是否重建，配置都能正确读回并显示。
     */
    private void refreshList() {
        loadConfig();
        buildAndSetAdapter();
    }

    private void buildAndSetAdapter() {
        appListAdapter = new AppListAdapter();
        recyclerView.setAdapter(appListAdapter);
        findViewById(R.id.progress_bar).setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void addAppInAllowList(String packageName){
        this.allowList.add(packageName);
        if (!updateConfig()) {
            this.allowList.remove(packageName); // 写盘失败则回滚内存，保证 UI 与磁盘一致
        }
    }

    private void deleteAppInAllowList(String packageName){
        this.allowList.remove(packageName);
        if (!updateConfig()) {
            this.allowList.add(packageName); // 回滚
        }
    }

    private boolean updateConfig(){
        try {
            ensureDefaultConfigValues();
            this.config.put("allowList", new JSONArray(this.allowList));
            // 1) 本地 SharedPreferences 始终写入 —— UI 的可靠来源，不依赖 LSPosed 远程通道
            boolean saved = localPref.edit()
                    .putBoolean("init", true)
                    .putStringSet("allowList", new HashSet<>(this.allowList))
                    .putBoolean("disableAutoCleanNotification", this.config.optBoolean("disableAutoCleanNotification", false))
                    .putBoolean("includeIceBoxDisableApp", this.config.optBoolean("includeIceBoxDisableApp", false))
                    .putBoolean("noResponseNotification", this.config.optBoolean("noResponseNotification", false))
                    .commit();
            if (!saved) {
                new AlertDialog.Builder(this).setTitle("更新配置文件失败").setMessage("本地配置写入失败").show();
                return false;
            }
            // 2) 尽力同步到 LSPosed 远程 Preferences（system_server 侧读取）。连接不上时静默，待 onServiceBind 时再 flush
            flushToRemote();
            return true;
        } catch (Throwable e) {
            Log.e("updateConfig", e.toString());
            new AlertDialog.Builder(this).setTitle("更新配置文件失败").setMessage(e.getMessage()).show();
            return false;
        }
    }

    /** 把本地配置同步到 LSPosed 远程 Preferences（system_server 读取用）。服务未连上时静默返回。 */
    private void flushToRemote() {
        SharedPreferences remote = getRemotePreferencesOrNull();
        if (remote == null) {
            return;
        }
        try {
            remote.edit()
                    .putBoolean("init", true)
                    .putStringSet("allowList", new HashSet<>(this.allowList))
                    .putBoolean("disableAutoCleanNotification", this.config.optBoolean("disableAutoCleanNotification", false))
                    .putBoolean("includeIceBoxDisableApp", this.config.optBoolean("includeIceBoxDisableApp", false))
                    .putBoolean("noResponseNotification", this.config.optBoolean("noResponseNotification", false))
                    .commit();
            this.sendBroadcast(new Intent("com.kooritea.fcmfix.update.config"));
        } catch (Throwable e) {
            Log.e("flushToRemote", e.toString());
        }
    }

    @Override
    public boolean onCreateOptionsMenu (Menu menu){
//      menu.add("隐藏启动器图标").setCheckable(true);

        menu.add("阻止应用停止时自动清除通知").setCheckable(true);

        menu.add("允许唤醒被冰箱冻结的应用").setCheckable(true);

//        menu.add("目标无响应时代发提示通知").setCheckable(true);

        menu.add("全选包含 FCM 的应用");

        menu.add("打开FCM Diagnostics");
        return true;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public final boolean onPrepareOptionsMenu(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if("隐藏启动器图标".equals(item.getTitle())){
                PackageManager packageManager = getPackageManager();
                item.setChecked(packageManager.getComponentEnabledSetting(new ComponentName("com.kooritea.fcmfix", "com.kooritea.fcmfix.Home")) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            }
            if("阻止应用停止时自动清除通知".equals(item.getTitle())){
                try {
                    item.setChecked(this.config.getBoolean("disableAutoCleanNotification"));
                } catch (JSONException e) {
                    item.setChecked(false);
                }
            }
            if("允许唤醒被冰箱冻结的应用".equals(item.getTitle())){
                try {
                    item.setChecked(this.config.getBoolean("includeIceBoxDisableApp"));
                } catch (JSONException e) {
                    item.setChecked(false);
                }
            }
            if("目标无响应时代发提示通知".equals(item.getTitle())){
                try {
                    item.setChecked(this.config.getBoolean("noResponseNotification"));
                } catch (JSONException e) {
                    item.setChecked(false);
                }
            }
            if("全选包含 FCM 的应用".equals(item.getTitle())){
                item.setOnMenuItemClickListener(menuItem -> {
                    for(AppInfo appInfo : appListAdapter.mAppList){
                        if(appInfo.includeFcm){
                            addAppInAllowList(appInfo.packageName);
                        }
                    }
                    appListAdapter.notifyDataSetChanged();
                    return false;
                });
            }
            if("打开FCM Diagnostics".equals(item.getTitle())){
                item.setOnMenuItemClickListener(menuItem -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage("com.google.android.gms");
                    intent.setComponent(new ComponentName("com.google.android.gms","com.google.android.gms.gcm.GcmDiagnostics"));
                    startActivity(intent);
                    return false;
                });
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public final boolean onOptionsItemSelected(MenuItem menuItem) {
        if(menuItem.getTitle().equals("隐藏启动器图标")){
            PackageManager packageManager = getPackageManager();
            packageManager.setComponentEnabledSetting(
                    new ComponentName("com.kooritea.fcmfix", "com.kooritea.fcmfix.Home"),
                    menuItem.isChecked() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
        }
        if(menuItem.getTitle().equals("阻止应用停止时自动清除通知")){
            try {
                this.config.put("disableAutoCleanNotification", !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e("onOptionsItemSelected",e.toString());
            }
        }
        if(menuItem.getTitle().equals("允许唤醒被冰箱冻结的应用")){
            try {
                this.config.put("includeIceBoxDisableApp", !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e("onOptionsItemSelected",e.toString());
            }
        }
        if(menuItem.getTitle().equals("目标无响应时代发提示通知")){
            try {
                this.config.put("noResponseNotification", !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e("onOptionsItemSelected",e.toString());
            }
        }
        return true;
    }
}