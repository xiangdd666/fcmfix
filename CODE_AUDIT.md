# fcmfix 代码审计报告

审计范围：`app/src/main/java/com/kooritea/fcmfix` 全部源码（Xposed 入口、各 hook 模块、UI、工具类）。
审计目标：功能正确性、稳定性、潜在崩溃、与 Android 16 / 小米 ROM 的适配风险。

---

## 🔴 严重（直接导致功能失效 / 数据丢失）

### 1. 勾选配置"假保存、真丢失"（与你之前报告的"勾选没了"现象完全吻合）
**文件**：`MainActivity.java`
**位置**：`onClick` (236-246) → `addAppInAllowList`/`updateConfig` (297-328)，以及 `getRemotePreferencesOrNull` (57-67)

问题：
- `onClick` 里先执行 `appInfo.isAllow = !appInfo.isAllow` 把 UI 复选框翻转，**然后才**调 `updateConfig()`。
- `updateConfig()` 第一行 `getRemotePreferencesOrNull()`：若 `xposedService == null`（LSPosed 服务没连上 / 绑定慢 / **Android 16 上远程 Preferences 绑不上**），直接抛 `IllegalStateException("XposedService 未连接")`，被 catch 后只弹一个"更新配置文件失败"对话框。
- 结果：复选框看着勾上了，但实际**没写进任何地方**。重开 App 配置是空的 → 就是"勾选没了"。

在 Android 16 + LSPosed 远程 Preferences 不稳的环境里，服务经常绑不上，于是**每次点击都被静默丢弃**。这是该现象最可能的根因，独立于 LSPosed 版本问题。

**修复建议**：
1. 写入成功前**不要**翻转 UI，等 `commit()` 返回 true 再翻转；
2. `xposedService` 为 null 时把勾选动作**入队**，等服务 `onServiceBind` 后重放；
3. 写入失败给出**更明显、不可忽略**的提示，而不是一闪而过的对话框。

### 2. 配置加载完成后界面不刷新（同上现象的第二个成因）
**文件**：`MainActivity.java`
**位置**：`AppListAdapter` 构造 (168-226)、`onServiceBind` (69-93)、`onBindViewHolder` (250-258)

问题：
- `onCreate` 用 `postDelayed(..., 1000)` 构建 `AppListAdapter`，用**当时** `allowList` 的快照填充每个 `AppInfo.isAllow`。
- LSPosed 服务晚于 1 秒连上时，`onServiceBind` → `loadConfigFromRemotePreferences()` 更新了 `MainActivity.allowList` 字段，但只调 `appListAdapter.notifyDataSetChanged()`。
- `onBindViewHolder` 读的是 `mAppList` 里**早已生成**的 `appInfo.isAllow`，不随 `allowList` 变化。`notifyDataSetChanged()` 仅重绑定，不重建列表 → 勾选框永远不显示已保存的配置。

**修复建议**：在 `onServiceBind` 里**重新 `new AppListAdapter()` 并 `recyclerView.setAdapter(...)`**；或在 `onBindViewHolder` 中直接 `allowList.contains(packageName)` 判断，而不是用缓存的 `appInfo.isAllow`。

### 3. 小米专属：PowerkeeperFix 实际是空操作（红米/小米用户最该关心的）
**文件**：`PowerkeeperFix.java`
**位置**：`methodHook` (40-84)，构造器 hook (86)

问题：
- 它 hook 的是 `MilletPolicy` 的**构造函数**，但逻辑写在 `beforeHookedMethod` 里，并错误调用了 `super.afterHookedMethod(methodHookParam)`（基类空实现）。
- 构造器的 `beforeHookedMethod` 在**构造体执行之前**运行，此时对 `mSystemBlackList` / `whiteApps` / `mDataWhiteList` 的 remove/add，会被紧接着执行的构造体初始化**全部覆盖**。
- 结果：在 MIUI / HyperOS 上，本应做的"把 GMS 移出省电黑名单、加入数据白名单"**完全不生效**，系统照样可能杀 GMS、限制推送。

**修复建议**：把字段修改逻辑移到 `afterHookedMethod`（构造体执行之后）再改，才能保留。

---

## 🟠 高（稳定性 / 崩溃风险）

### 4. ReconnectManagerFix 每个 GCM 闹钟都 new 一个 Timer 线程
**文件**：`ReconnectManagerFix.java` (204-216)
`afterHookedMethod` 内 `new Timer("ReconnectManagerFix")` 然后 schedule，任务跑完才 `cancel()`。GCM 心跳/重连闹钟几分钟一次，等于持续产生短命线程。
**修复**：用单个静态 `ScheduledExecutorService` 或复用 Timer；或改用 AlarmManager 重新调度。

### 5. AutoStartFix `isAllowStartService` 未判空 → NPE
**文件**：`AutoStartFix.java` (116)
`String target = intent.getComponent().getPackageName();` 未判断 `getComponent() == null`。一旦为 null 抛 NPE，可能中断广播分发。
**修复**：`intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();`（参照同文件其他分支写法）。

---

## 🟡 中（健壮性问题）

### 6. ReconnectManagerFix 反射"猜"下一个连接时间字段不可靠
**文件**：`ReconnectManagerFix.java` (194-203)
遍历 `timerClazz` 所有 `long` 字段，挑"值最大"的当成下次连接时间字段。若该类有多个 long 字段，可能选错，`getLongField` 取到错误值，导致 `GCM_RECONNECT` 广播时机错误。
**修复**：用已知字段名定位，而非"最大值启发式"。

### 7. ReconnectManagerFix 构造器反射易越界
**文件**：`ReconnectManagerFix.java` (239)
`heartbeatChimeraAlarm.getConstructors()[0]` 直接取第一个构造器，再取 `[3]` 参数类型。若第一个构造器参数不足 4 个 → `ArrayIndexOutOfBoundsException`，`findAndUpdateHookTarget` 整体失败 → 重连修复被 disabled。
**修复**：遍历构造器找参数长度 ≥ 4 的那个。

### 8. `isBootComplete` 开机后 60 秒窗口漏推送
**文件**：`XposedModule.java` (85-93)、`BroadcastFix.java` (132)
system_server 侧 `isBootComplete` 要等 60 秒线程才置 true，期间所有 FCM 广播不强制启动。重启后一分钟内来的推送会被丢掉。
**修复**：缩短等待，或监听用户解锁即置位。

### 9. `getStringSet` 返回不可变集合的隐患
**文件**：`XposedModule.java` (187)
`allowList = remotePreferences.getStringSet("allowList", ...)` 在较新 Android 上返回受管/不可变集合。当前只 `contains()` 读，没问题；但任何路径若对它 `add/remove` 会抛 `UnsupportedOperationException`。
**修复**：`allowList = new HashSet<>(remotePreferences.getStringSet(...));`

---

## ⚪ 低（代码质量 / 误导）

### 10. `BroadcastFix.java:114` 误用单 `&`
`intent_args_index != 0 & appOp_args_index != 0` 应为 `&&`。当前因运算符优先级不崩，但语义错误、易埋雷。

### 11. `MainActivity.java:169-170` 死代码
`allowListSet.containsAll(allowList);` 返回值未使用，纯废行。

### 12. `BootCompletedReceiver.java` 空实现
只打一行 log，不发任何实际作用（配置是按需加载的）。可删，免得误导。

### 13. `MainActivity.java:222` 过时提示
弹窗提示"编辑 `filesDir/config.json`"，但代码早已改用 LSPosed 远程 Preferences，该文件不存在也不会被读。误导用户，应改为远程配置说明或删除。

### 14. `XposedUtils.java:132-138` 误导性异常
`getObjectFieldByPath(obj, path, clazz)` 类型不匹配时抛 `NoSuchFieldError`，语义不符，建议用 `ClassCastException`。

---

## 配置 / 构建侧
- `compileSdk = 36`、`targetSdk = 34`：Xposed 模块常见组合，编译无碍。
- `QUERY_ALL_PACKAGES` 已声明（`AndroidManifest.xml:7`），包可见性不是问题（列表为空不是这个原因）。
- `BootCompletedReceiver` 已注册但无用（见 #12）。

---

## 优先级建议
1. 先修 **#1 + #2**（配置丢失，用户已实锤复现）。
2. 小米用户务必修 **#3**（PowerkeeperFix 空操作，省电修复无效）。
3. 再处理 **#4 / #5**（线程泄漏、NPE）。
4. 其余逐步清理。

---

## 已修复记录（2026-07-21 修复会话）

以下项已在本会话中直接修改源码修复：

| 编号 | 问题 | 修复方式 |
|------|------|----------|
| #1 | MainActivity 配置假保存/丢失 | 点击先入队、绑定后再落盘；失败回滚 UI；`allowList.contains()` 驱动勾选态 |
| #2 | 配置加载后界面不刷新 | `onServiceBind` 重建 Adapter（`buildAndSetAdapter`）而非仅 notify |
| #3 | PowerkeeperFix 构造器空操作 | 字段修改从 `beforeHookedMethod` 移至 `afterHookedMethod` |
| #4 | ReconnectManagerFix 线程泄漏 | 复用单例 `ScheduledExecutorService` 替代每次 `new Timer()` |
| #5 | AutoStartFix `getComponent()` NPE | 增加 `== null` 判空分支 |
| #9 | `getStringSet` 不可变集合 | 包一层 `new HashSet<>()` |
| #10 | BroadcastFix 单 `&` 误用 | 改为 `&&` |
| #13 | MainActivity 过时 config.json 提示 | 改为 LSPosed 远程 Preferences 说明 |
| #16 | `updateConfig` 用 `config.getBoolean` 读空 JSONObject 抛 JSONException，导致 `commit()` 未执行、配置写不进 | 写盘前 `ensureDefaultConfigValues()` 兜底；三处 `getBoolean` 改 `optBoolean(key, false)` |
| #17 | 配置读写强依赖 `XposedService` 远程通道，Activity 重建后 `refreshList` 因服务未连上而空返回，勾选丢失 | UI 读写改走 App 自身 `SharedPreferences`（本地，永远可用）；远程 Preferences 仅作 system_server 同步的"尽力而为"职责，`onServiceBind` 时 flush |

### 新增 #15（运行时日志实锤）：ReconnectManagerFix `getWindow` NoSuchMethodError
**文件**：`ReconnectManagerFix.java`（原 `addButton()` 第 303 行）
**日志现象**：`java.lang.NoSuchMethodError: com.google.android.gms.gcm.GcmChimeraDiagnostics#getWindow`
**根因**：`libxposed` 的 `XposedHelpers.findBestMethod`（XposedHelpers.java:186）只遍历 `clazz.getDeclaredMethods()`，**不递归父类**；而 `getWindow()` 是 `Activity` 父类方法，`GcmChimeraDiagnostics` 自身未声明，故 `callMethod(thisObject, "getWindow")` 必抛 `NoSuchMethodError`。GMS 版本 26.26.34 暴露此问题。
**影响**：每次打开 GMS 诊断界面（`GcmChimeraDiagnostics`）抛一次异常刷日志；诊断界面内「RECONNECT / 打开FCMFIX」两个按钮永远加不上。**不影响核心重连逻辑**（在 `startHook` 内）。
**修复**：改用 `((Activity) param.thisObject).getWindow()` 直接转型调用（绕开 `callMethod` 反射查找），并在 `afterHookedMethod` 整体包 try-catch + `instanceof Activity` 防御。已删除无用 `ContextWrapper` import。

> 共性问题提醒：`libxposed` 的 `callMethod` / `callStaticMethod` 不递归父类，是标准 LSPosed 的差异点。代码库内另两处 `callMethod`（`OplusProxyFix.unfreezeIfNeed` 一加专属、`AutoStartFix.checkAbnormalBroadcastInQueueLocked` AMS 自身声明方法）在当前小米 Android 16 环境不触发此坑，但后续若遇同类 `NoSuchMethodError` 可优先排查此根因；必要时将 `findBestMethod` 改为递归父类可根治。

> ⚠️ 本机无 Android SDK，未编译验证；请在实机/有 SDK 环境出包后验证。

### 新增 #16（安装后实锤）：勾选应用弹「更新配置文件失败 No value for disableAutoCleanNotification」
**文件**：`MainActivity.java` → `updateConfig()`
**现象**：安装后勾选任意应用，弹窗「更新配置文件失败 No value for disableAutoCleanNotification」，且勾选未保存。
**根因**：`updateConfig()` 用 `this.config.getBoolean("disableAutoCleanNotification")` 读开关；`config` 初始为**空 `JSONObject`**，该 key 不存在时 `JSONObject.getBoolean` 抛 `JSONException: No value for ...`，使 `commit()` 根本没执行。
之所以 `config` 会为空：本类 `xposedService` 是**静态字段**；当 Activity 被重建（被杀后台/旋转屏）而 LSPosed 服务早已连上时，`onServiceBind` 不再触发，`loadConfigFromRemotePreferences()`（其内 `ensureDefaultConfigValues()` 才会给 `config` 填默认值）不会被调用，于是 `config` 是新实例的空对象，而 `xposedService != null` 让点击直接走 `updateConfig()` → 抛异常。
**修复**：`updateConfig()` 写盘前先调 `ensureDefaultConfigValues()` 兜底；三处 `config.getBoolean(key)` 全部改为 `config.optBoolean(key, false)`（key 缺失返回默认 `false`、不抛异常）。如此无论 `config` 是否被填充，勾选都能正常落盘。

### 新增 #17（用户多次复现"勾选后返回没了"）：配置存储强依赖 LSPosed 远程通道
**文件**：`MainActivity.java`
**现象**：勾选应用、返回再打开，应用未被勾选（之前 `#1/#2/#16` 的修复仍未能根治）。
**根因**：`loadConfigFromRemotePreferences()`（读）与 `updateConfig()`（写）全部走 `xposedService.getRemotePreferences("config")`；而 `refreshList()` 第一行 `if (xposedService == null) return;`。在 Android 16 上 App 侧 `XposedService` 绑定时机不稳/偏晚，Activity 重建后 `onServiceBind` 未回调或 `onResume` 时服务仍为空，`refreshList()` 直接空返回 → 用空 `allowList` 建列表 → 全不勾选。`libxposed` 也没有 `XSharedPreferences`，无法用标准只读共享方案替代。
**修复**：
- 新增 `localPref = getSharedPreferences("config", MODE_PRIVATE)`，作为 **UI 的可靠来源**。
- `loadConfig()` 改读本地 `localPref`（不再依赖服务）；`updateConfig()` 先写本地 `localPref` 再 `flushToRemote()` 尽力同步到远程。
- `refreshList()` / `onResume` / `onCreate` 兜底**移除对 `xposedService == null` 的提前返回**，始终用本地配置构建列表。
- `onServiceBind` 时调用 `flushToRemote()`：把本地配置推到远程 Preferences 并发 `com.kooritea.fcmfix.update.config` 广播，使 system_server 侧 `onUpdateConfig` 能读到最新 allowList。
- 移除"待应用队列"（`pendingAdd`/`pendingRemove`）与"XposedService 未连接"弹窗——本地写入永远成功，远程同步失败静默处理。
**效果**：勾选一定存得住、读得回，与 `XposedService` 连不连上无关。若 `XposedService` 始终连不上，UI 仍正确，但 system_server 侧配置需等服务连上后 flush 才生效（属原模块的固有跨进程依赖）。

