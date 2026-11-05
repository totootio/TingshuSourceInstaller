# 听书源导入器 · TingshuSourceInstaller

> 无 root 环境下，把 jar 听书源导入到「我的听书」（`com.github.eprendre.tingshu`）的
> `/sdcard/Android/data/com.github.eprendre.tingshu/files/jars/` 插件目录。

本仓库包含该导入 APK 的**完整构建工程**与一份**关于「如何拿到授权」的说明**。
APK 不依赖 Gradle / Android Studio，用一套手工构建脚本即可从源码生成。

---

## 1. 背景：为什么这件事这么麻烦

Android 11（API 30）起引入**分区存储（Scoped Storage）**，外部存储被重新划分：

- 应用自己的 `/sdcard/Android/data/<自己的包名>` 可以读写；
- **其他应用**的 `/sdcard/Android/data/<别的包名>` 被系统（FUSE / sdcardfs）按 UID 隔离，
  普通 App 用 `File` API 读不到、写不进。

于是把 jar 塞进「我的听书」的 `jars/` 目录，就卡在了「**怎么合法访问别的 App 的 Android/data**」上。

> 实测设备：Xiaomi 23013RK75C / Android 15 / 安全补丁 2026-05-01。
> 该机型上，系统文件选择器（DocumentsUI）会**隐藏 `Android/data`**，并把「使用此文件夹」按钮**置灰**。

---

## 2. 关于 MT 管理器：它所谓的「两个权限」到底是什么

很多人觉得「MT 管理器只要点两个权限就能进 Android/data」。把 MT 官方 APK 拆开分析、
结合实测录屏，真相是：

| 序号 | 权限 / 能力 | 说明 |
|---|---|---|
| ① | **所有文件访问权限**（`MANAGE_EXTERNAL_STORAGE`） | 设置里一个开关，给 App 广泛的外置存储访问权 |
| ② | **目录访问授权（SAF）** 或 **Shell 权限（Shizuku）** | MT 内置了完整 Shizuku 引擎（`bin.mt.shell.IShizukuCommand`），可借 `shell`(UID 2000) 身份代写 |

关键澄清：

- ① 单独开启**并不能**让你用 `File` API 写别的 App 的 `Android/data` —— FUSE 仍按 UID 隔离；
- MT 真正能写进去，靠的是它**内置的 Shell 权限 / Shizuku / 自绘文件列表**，而不是系统授权框；
- **普通独立 APK 无法复刻 MT 这套内部通道**，系统 DocumentsUI 对第三方 App 就是不让选 `Android/data`。

> 结论：所谓「两个权限就完了」，是 MT 这个**本身已具备 Shell 能力**的特殊 App 才能做到的事。

---

## 3. 本应用如何处理授权（v12.1 方案）

v12.1 在 v12 基础上移除了第③步的**「文件名前缀」输入框**及其示例/提示文字，界面更简洁：
第③步卡片现在只保留标题+状态徽标、**＋ 选择 jar 文件（可多选）** 按钮（青）、**⬇ 导入 jar** 按钮（绿）、
以及已选源列表；导入时按**原文件名**逐条写入 `jars/`。v12 已完成的改动仍保留：第③步两个按钮从
「并排挤在一行」改为**各占一行全宽**的圆角按钮；移除了会引发布局抖动的进度条，改用状态卡文字表达忙碌；
图标为青绿圆角方块 + 白色「向下导入箭头 + 目录托盘 + 听书圆点」。
**批量导入**能力保留：第③步文件选择器开启多选，可一次选中多个 jar，统一写入 `jars/`
（各自保留原文件名），导入时逐条统计成功/失败。授权流程仍是**三步引导卡**
（① 开权限 → ② 授权 → ③ 选 jar 并导入），每步带「完成/待办」徽标与一键重试，
诊断日志可折叠。底层**只靠系统提供的两条合法通道**，
打开后自动按序探测，并在界面给出大字结论。三档通道如下：

| 通道 | 原理 | 是否需要用户授权 | 适用情况 |
|---|---|---|---|
| **A. ContentResolver 直连** | 直接请求 `ExternalStorageProvider` 的 tree URI，用 `DocumentsContract.createDocument` 写入 | 无需任何弹框 | ROM 允许 `primary:Android/data` 直接解析时（少数机型） |
| **B. 零宽 docId 授权** | 用 `U+200B` 零宽字符插进 docId（`primary:A\u200Bndroid/data`）骗过 DocumentsUI 的黑名单，归一化后落到真实目录 | 需点一次「使用此文件夹」→「允许」 | 大多数 Android 11+ 机型（灰按钮问题的绕过） |
| **C. 零宽字符路径直写** | 在文件路径里插零宽字符，由 FUSE 归一化回真实目录 | 不需要 | 作为前两者都失败时的兜底 |

### 关于「零宽 docId」绕过灰按钮

DocumentsUI 是按**字符串**匹配 `Android/data` 来决定隐藏和置灰的。把 `U+200B`（`\u200B`）
插进 `Android` 中间，例如 `primary:A\u200Bndroid/data`：

- DocumentsUI 的字符串黑名单**认不出**这个变体 → 按钮不再变灰；
- `ExternalStorageProvider` 在归一化时会丢弃这个「可忽略码点」→ 仍指向真实的 `Android/data`。

本应用共内置 **8 个 docId 变体**（如 `A\u200Bndroid`、`Android/\u200bdata`、`Android/da\u200bta` 等），
点一次②若仍灰，返回再点会自动换下一个变体。

> ⚠️ **已知边界（务必阅读）**
> 这个技巧依赖 ROM 的 FUSE 归一化实现。部分较新的安全补丁（如 2026-05 这类）可能已封堵该通道，
> 此时 8 个变体全部置灰。这种情况下，**普通 APK 在无 root、纯授权前提下没有可行路径**。
> 最省事的替代方案是：直接用 MT 管理器把 jar 拷进去，或走一次性 `adb push` / 官方订阅源。

---

## 4. 使用步骤

大多数情况下**只需 1 个权限**：

1. 安装 APK（v12.1+，支持批量），打开后看顶部状态条。
2. 若提示 `① 所有文件访问权限：未开启`：
   - 点 **①**，在系统设置里打开「所有文件访问权限」开关，返回。
3. 看状态条结论：
   - **绿色 `✓ 可写入`** → 进入第③步选 jar；
   - **橙色 `请点②授权`** → 点 **②**，系统选择器定位到零宽 docId，按钮应当可点，
     选 `Android/data`（或停在 `Android`）→「使用此文件夹」→「允许」；
   - 若某变体仍灰 → 返回**再点一次②**，自动换下一个变体。
4. **第③步：批量选择并导入**（两个按钮已分两行全宽显示）
   - 点 **「＋ 选择 jar 文件（可多选）」**，在文件选择器里勾选**一个或多个** jar（长按/多选视 ROM 而定）；
   - 已选列表会显示在卡片里，格式为 `已选 N 个源：…`；
   - 点 **「⬇ 导入 N 个 jar」**（绿色按钮），应用会按原文件名逐个写入 `jars/` 并统计 `成功 X / 失败 Y`。
5. 导入成功后，**清后台并彻底重启「我的听书」**，它才会扫描 `jars/` 加载新源。

排错：点 **「复制日志」** 发回，日志会直接打印权限状态、每条通道的确切异常，以及每个文件的成败。

---

## 5. 构建方法

环境要求（Ubuntu / Debian 验证通过）：

- JDK 8+（`javac` 以 `-source 8 -target 8` 编译）
- Android `build-tools 34.0.0`（`aapt2` / `d8` / `zipalign` / `apksigner`）
- Android `platform android-34` 的 `android.jar`

把工具链放到 `build.sh` 里 `ANDK` 指向的目录（`bt/android-14` 放 build-tools，`pl/android-34-ext12` 放 android.jar），
然后：

```bash
cd TingshuSourceInstaller
./build.sh
# 产物：/workspace/TingshuSourceInstaller.apk（受 ANDK / FINAL_APK 环境变量控制）
```

签名密钥说明：

- **`ts.keystore` 是私钥，已加入 `.gitignore`，不会进入本仓库**；
- `build.sh` 在发现本地缺少 keystore 时会**自动生成**一个（`alias=tskey`，有效期 10000 天）；
- 因此任何人 `clone` 后都能构建出可安装的 APK，只是签名密钥各不相同。

---

## 6. 安全与隐私

- 应用**不申请任何网络权限**，不会上传任何数据；
- 所有文件操作仅限本机存储与 `Android/data/com.github.eprendre.tingshu/files/jars/`；
- 不内置、不依赖 Shizuku，不请求 root；
- 导入的 jar 由「我的听书」自行加载，风险请自行评估来源。

---

## 7. 文件结构

```text
TingshuSourceInstaller/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/top/totootao/tingshu/installer/MainActivity.java
│       └── res/mipmap-{m,hd,xh,xxh,xxxh}dpi/ic_launcher(.round).png  # 应用图标
├── build.gradle
├── settings.gradle
├── build.sh            # 手工构建脚本（aapt2 → javac → d8 → zipalign → apksigner）
├── .gitignore          # 忽略 ts.keystore / build/ / libs/ / *.apk
└── README.md
```

> `libs/`（早期 v7 的 Shizuku 依赖）在本仓库被忽略，自 v8 起已移除该依赖。
