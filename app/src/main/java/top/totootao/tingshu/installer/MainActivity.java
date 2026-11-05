package top.totootao.tingshu.installer;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 听书源导入器 v11 —— 支持批量（多个 jar）导入，三步引导式操作。
 *
 * 依据（lbchs5343/Android-FileExplorerDemo，2024-05-18 更新）：
 *   Android 13/14 上可用零宽字符插入 docId 来直接授权 Android/data。
 *   DocumentsUI 的字符串黑名单不认这个变体，「使用此文件夹」不再变灰；
 *   ExternalStorageProvider 归一化后仍映射到真实 Android/data。
 *
 * 用户 v7 日志已证明：该机零宽路径能列出真实 Android/data 的 176 个包名子项，
 * 即可忽略码点归一化在这台 Xiaomi Android 15 上确实生效。
 *
 * 通道：
 *   0. ContentResolver 直连 ExternalStorageProvider（无需任何授权框）
 *   1. 真实路径 File API 直写
 *   2. SAF tree URI 写入（零宽 docId 授权后，由系统 provider 代劳 create）
 *   3. 零宽路径 File API 兜底
 *
 * 不依赖 Shizuku / root / 开发者选项 / 无线调试；不自动重启进程（v8.0 白屏原因）。
 */
public class MainActivity extends Activity {

    private static final String PREF = "ts9";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_JARS = "jar_uris";        // 多行：每行一个 content uri
    private static final String KEY_SAF_IDX = "saf_idx";

    private static final String TARGET_PKG = "com.github.eprendre.tingshu";
    private static final String REL = "Android/data/" + TARGET_PKG + "/files/jars";
    private static final String ES_AUTHORITY = "com.android.externalstorage.documents";
    private static final String TEST_NAME = "ts9_write_test.bin";

    private static final int REQ_TREE = 9001;
    private static final int REQ_JAR = 9002;
    private static final int CODE_ALLFILES = 1;
    private static final int CODE_TREE = 2;
    private static final int CODE_PICK = 3;
    private static final int CODE_IMPORT = 4;
    private static final int CODE_LOG = 5;
    private static final int CODE_REFRESH = 6;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int HORIZONTAL = LinearLayout.HORIZONTAL;

    private static final int C_BG = Color.parseColor("#ECEFF1");
    private static final int C_CARD = Color.WHITE;
    private static final int C_TITLE = Color.parseColor("#263238");
    private static final int C_SUB = Color.parseColor("#78909C");
    private static final int C_TEXT = Color.parseColor("#37474F");
    private static final int C_BLUE = Color.parseColor("#607D8B");
    private static final int C_GREEN = Color.parseColor("#2E7D32");
    private static final int C_ORANGE = Color.parseColor("#EF6C00");
    private static final int C_RED = Color.parseColor("#C62828");
    private static final int C_GRAY = Color.parseColor("#90A4AE");
    private static final int C_TEAL = Color.parseColor("#00897B");
    private static final int C_TEAL_BG = Color.parseColor("#E0F2F1");

    /** 零宽/可忽略码点，用于构造绕过 DocumentsUI 黑名单的 docId */
    private static final String[] IGN = {
            "\u200B", "\u200C", "\u200D", "\u2060", "\uFEFF", "\u00AD", "\u034F"
    };

    /** SAF 定位变体：{docId, 中间补位}。已定位到 data 层用 "/"，只到 Android 层用 "/data/" */
    private static final String[][] SAF_VARIANTS = {
            {"primary:A\u200Bndroid/data", "/"},
            {"primary:Android/\u200Bdata", "/"},
            {"primary:Android/da\u200Bta", "/"},
            {"primary:Android/data\u200B", "/"},
            {"primary:A\u200Cndroid/data", "/"},
            {"primary:A\u200Bndroid", "/data/"},
            {"primary:Android", "/data/"},
            {"primary:Android/data", "/"},
    };

    private SharedPreferences prefs;
    private TextView statusView;
    private ProgressBar progressBar;
    private TextView targetView;
    private TextView step1Badge, step2Badge, step3Badge;
    private Button step1Btn, step2Btn, step3Btn, importBtn;
    private TextView selectedView;
    private EditText prefixEdit;
    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout logContainer;
    private TextView logHeader;
    private final StringBuilder logBuf = new StringBuilder();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private volatile boolean busy = false;
    private boolean suppressAuto = false;
    private volatile String lastSafDocId = null;

    // 引导状态
    private boolean afOk = false;
    private boolean treeOk = false;
    private final List<Uri> selectedUris = new ArrayList<Uri>();
    private final List<String> selectedNames = new ArrayList<String>();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        setContentView(buildUi());
        restoreSelected();
        refreshStateFlags();

        log("=== 听书源导入器 v11.0（支持批量导入多个 jar）===");
        log("设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        log("目标: /storage/emulated/0/" + REL + "/");
        log("");
        log("第③步支持多选：一次选多个 jar，统一导入到 jars 目录（各自保留原文件名）。");
        log("不需要 Shizuku / root / 开发者选项 / 无线调试，也不会自动重启。");
        cleanupPendingRestart();
        updateSteps();
        updateSelectedView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (suppressAuto) { suppressAuto = false; return; }
        if (!busy) run(new Runnable() { @Override public void run() { auto(); } });
    }

    /** 清理 v8.0 遗留的「自动重启」闹钟，防止旧版残留继续拉起本应用 */
    private void cleanupPendingRestart() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch == null) return;
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, launch, flags);
            if (pi != null) {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Throwable ignored) { }
    }

    private void refreshStateFlags() {
        afOk = hasAllFiles();
        treeOk = savedTree() != null;
    }

    /** 从持久化（多行 uri）恢复已选文件列表 */
    private void restoreSelected() {
        selectedUris.clear();
        selectedNames.clear();
        String s = prefs.getString(KEY_JARS, null);
        if (s == null || s.isEmpty()) return;
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                Uri u = Uri.parse(line);
                selectedUris.add(u);
                selectedNames.add(queryName(u));
            } catch (Throwable ignored) { }
        }
    }

    /** 把已选 uri 列表持久化为多行字符串 */
    private void persistSelected() {
        StringBuilder sb = new StringBuilder();
        for (Uri u : selectedUris) sb.append(u.toString()).append('\n');
        prefs.edit().putString(KEY_JARS, sb.toString()).apply();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(C_BG);
        rootScroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        // 标题
        TextView title = new TextView(this);
        title.setText("听书源导入器");
        title.setTextSize(20);
        title.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        title.setTextColor(C_TITLE);
        root.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("把 jar 听书源导入「我的听书」（支持批量）");
        subtitle.setTextSize(12);
        subtitle.setTextColor(C_SUB);
        subtitle.setPadding(0, dp(2), 0, dp(6));
        root.addView(subtitle);

        // 状态卡
        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(14), dp(16), dp(14), dp(16));
        statusView.setTextColor(Color.WHITE);
        statusView.setBackgroundColor(C_BLUE);
        statusView.setText("正在检测…");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, WRAP);
        root.addView(statusView, slp);

        // 进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(MATCH, WRAP);
        pblp.topMargin = dp(6);
        root.addView(progressBar, pblp);

        // 目标目录卡
        LinearLayout targetCard = card();
        TextView tlabel = new TextView(this);
        tlabel.setText("jar 将放入以下目录");
        tlabel.setTextSize(11); tlabel.setTextColor(C_SUB);
        targetView = new TextView(this);
        targetView.setText("/storage/emulated/0/" + REL + "/");
        targetView.setTextSize(12); targetView.setTextColor(C_TEXT);
        targetView.setTypeface(Typeface.MONOSPACE);
        targetView.setPadding(0, dp(4), 0, 0);
        targetView.setTextIsSelectable(true);
        targetCard.addView(tlabel); targetCard.addView(targetView);
        root.addView(targetCard);

        // 步骤一：所有文件访问权限
        LinearLayout s1 = card();
        LinearLayout s1row = new LinearLayout(this); s1row.setOrientation(HORIZONTAL);
        TextView s1t = stepTitle("① 所有文件访问权限");
        step1Badge = badge("请开启");
        s1row.addView(s1t); s1row.addView(step1Badge);
        step1Btn = primaryButton("去开启权限", CODE_ALLFILES, false);
        s1.addView(s1row); s1.addView(step1Btn);
        addHint(s1, "设置里打开「所有文件访问权限」开关后返回即可。");
        root.addView(s1);

        // 步骤二：授权 Android/data
        LinearLayout s2 = card();
        LinearLayout s2row = new LinearLayout(this); s2row.setOrientation(HORIZONTAL);
        TextView s2t = stepTitle("② 授权 Android/data");
        step2Badge = badge("待授权");
        s2row.addView(s2t); s2row.addView(step2Badge);
        step2Btn = primaryButton("一键授权（灰了就再点换变体）", CODE_TREE, false);
        s2.addView(s2row); s2.addView(step2Btn);
        addHint(s2, "选择器里选 Android/data，点「使用此文件夹」→「允许」。若仍灰，返回再点一次会自动换下一个变体。");
        root.addView(s2);

        // 步骤三：选择并导入（重点突出，支持多选）
        LinearLayout s3 = card();
        s3.setBackgroundColor(C_TEAL_BG);
        LinearLayout s3row = new LinearLayout(this); s3row.setOrientation(HORIZONTAL);
        TextView s3t = stepTitle("③ 选择 jar（可多选）并导入");
        step3Badge = badge("待选择");
        s3row.addView(s3t); s3row.addView(step3Badge);
        s3.addView(s3row);

        LinearLayout btnRow = new LinearLayout(this); btnRow.setOrientation(HORIZONTAL);
        step3Btn = primaryButton("选择 jar 文件（可多选）", CODE_PICK, true);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        step3Btn.setLayoutParams(b1);
        importBtn = primaryButton("导入", CODE_IMPORT, true);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        b2.leftMargin = dp(8);
        importBtn.setLayoutParams(b2);
        btnRow.addView(step3Btn);
        btnRow.addView(importBtn);
        s3.addView(btnRow);

        selectedView = new TextView(this);
        selectedView.setTextSize(12);
        selectedView.setTextColor(C_TEXT);
        selectedView.setTypeface(Typeface.MONOSPACE);
        selectedView.setPadding(0, dp(6), 0, dp(2));
        s3.addView(selectedView);

        TextView prefixLabel = new TextView(this);
        prefixLabel.setText("文件名前缀（可选，留空则用原名；多个文件会加前缀便于区分）");
        prefixLabel.setTextSize(11); prefixLabel.setTextColor(C_SUB);
        prefixLabel.setPadding(0, dp(6), 0, dp(2));
        s3.addView(prefixLabel);
        prefixEdit = new EditText(this);
        prefixEdit.setHint("例如 book_  →  得到 book_xxx.jar");
        prefixEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        prefixEdit.setTextSize(14);
        s3.addView(prefixEdit);
        addHint(s3, "选完多个 jar 后点「导入」统一写入。成功请彻底重启「我的听书」让它扫描 jars/。");
        root.addView(s3);

        // 次级按钮行
        LinearLayout secRow = new LinearLayout(this); secRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        srLp.topMargin = dp(10);
        Button bRefresh = secondaryButton("重新检测", CODE_REFRESH);
        Button bLog = secondaryButton("复制日志", CODE_LOG);
        secRow.addView(bRefresh, srLp);
        LinearLayout.LayoutParams srLp2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        srLp2.topMargin = dp(10); srLp2.leftMargin = dp(8);
        secRow.addView(bLog, srLp2);
        root.addView(secRow);

        // 诊断日志（可折叠）
        logHeader = new TextView(this);
        logHeader.setText("诊断日志 ▼  点我展开/收起");
        logHeader.setTextSize(13);
        logHeader.setTextColor(C_TEAL);
        logHeader.setPadding(0, dp(12), 0, dp(4));
        logHeader.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean vis = logContainer.getVisibility() == View.VISIBLE;
                logContainer.setVisibility(vis ? View.GONE : View.VISIBLE);
                logHeader.setText(vis ? "诊断日志 ▼  点我展开/收起" : "诊断日志 ▲  点我展开/收起");
            }
        });
        root.addView(logHeader);

        logContainer = new LinearLayout(this);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#212121"));
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.parseColor("#D6D6D6"));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView);
        logContainer.addView(logScroll);
        LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(MATCH, dp(170));
        lclp.topMargin = dp(2);
        logContainer.setLayoutParams(lclp);
        logContainer.setVisibility(View.GONE);
        root.addView(logContainer);

        rootScroll.addView(root);
        return rootScroll;
    }

    // ---- UI 小构件 ----
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(C_CARD);
        int p = dp(12); c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView stepTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(15);
        v.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        v.setTextColor(C_TITLE);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        return v;
    }

    private TextView badge(String t) {
        TextView b = new TextView(this);
        b.setText(t); b.setTextSize(12);
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        b.setTextColor(Color.WHITE); b.setBackgroundColor(C_GRAY);
        return b;
    }

    private void setBadge(TextView b, String t, int color) {
        b.setText(t); b.setBackgroundColor(color);
    }

    private void addHint(LinearLayout card, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(11); h.setTextColor(C_SUB);
        h.setPadding(0, dp(6), 0, 0);
        card.addView(h);
    }

    private Button primaryButton(String text, final int code, boolean strong) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(strong ? 16 : 14);
        b.setMinHeight(dp(48));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setBackgroundColor(strong ? C_TEAL : C_BLUE);
        b.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(code); }
        });
        return b;
    }

    private Button secondaryButton(String text, final int code) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(42));
        b.setBackgroundColor(Color.parseColor("#CFD8DC"));
        b.setTextColor(C_TITLE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(code); }
        });
        return b;
    }

    private void updateSteps() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setBadge(step1Badge, afOk ? "✓ 已开启" : "请开启",
                        afOk ? C_GREEN : C_ORANGE);
                if (treeOk) setBadge(step2Badge, "✓ 已授权", C_GREEN);
                else if (afOk) setBadge(step2Badge, "需要授权", C_ORANGE);
                else setBadge(step2Badge, "先开①", C_GRAY);
                int n = selectedUris.size();
                setBadge(step3Badge, n > 0 ? "✓ 已选 " + n + " 个" : "待选择",
                        n > 0 ? C_GREEN : C_GRAY);
            }
        });
    }

    private void updateSelectedView() {
        final int n = selectedUris.size();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (n == 0) {
                    selectedView.setText("尚未选择文件（可多选）");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("已选 ").append(n).append(" 个源：\n");
                    int m = Math.min(n, 8);
                    for (int i = 0; i < m; i++) {
                        String nm = selectedNames.get(i);
                        sb.append("  • ").append(nm == null ? selectedUris.get(i).toString() : nm).append('\n');
                    }
                    if (n > m) sb.append("  … 等 ").append(n).append(" 个\n");
                    selectedView.setText(sb.toString());
                }
                int cnt = selectedUris.size();
                importBtn.setText(cnt > 0 ? "导入 " + cnt + " 个 jar" : "导入");
                importBtn.setEnabled(cnt > 0 && !busy);
            }
        });
    }

    private void dispatch(final int code) {
        if (busy && code != CODE_LOG && code != CODE_REFRESH) { toast("正在执行，请稍候"); return; }
        switch (code) {
            case CODE_ALLFILES: openAllFilesSettings(); break;
            case CODE_TREE:     requestTree(); break;
            case CODE_PICK:     pickJar(); break;
            case CODE_IMPORT:   run(new Runnable() { @Override public void run() { doImportAll(); } }); break;
            case CODE_REFRESH:  run(new Runnable() { @Override public void run() { auto(); } }); break;
            case CODE_LOG:
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("ts9-log", logBuf.toString()));
                toast("日志已复制，可直接发回");
                break;
        }
    }

    private void openAllFilesSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) { log("!! 无法打开设置: " + t2); }
        }
    }

    /** 用零宽 docId 构造 EXTRA_INITIAL_URI；每次点②自动换下一个变体 */
    private void requestTree() {
        suppressAuto = true;
        int idx = prefs.getInt(KEY_SAF_IDX, 0);
        if (idx < 0 || idx >= SAF_VARIANTS.length) idx = 0;
        String docId = SAF_VARIANTS[idx][0];

        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        if (Build.VERSION.SDK_INT >= 26) {
            Uri init = new Uri.Builder()
                    .scheme("content")
                    .authority(ES_AUTHORITY)
                    .appendPath("tree").appendPath(docId)
                    .appendPath("document").appendPath(docId)
                    .build();
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, init);
            log("[" + (idx + 1) + "/" + SAF_VARIANTS.length + "] 定位: " + show(docId));
        }
        log("点『使用此文件夹』→ 弹窗点『允许』。若仍灰，返回再点②换下一个变体。");
        prefs.edit().putInt(KEY_SAF_IDX, idx + 1).apply();
        try { startActivityForResult(i, REQ_TREE); }
        catch (Throwable t) { log("!! 无法打开选择器: " + t); }
    }

    /** 多选 jar：开启 EXTRA_ALLOW_MULTIPLE，返回 ClipData（多个）或单个 uri */
    private void pickJar() {
        suppressAuto = true;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_JAR); }
        catch (Throwable t) { log("!! 无法打开文件选择器: " + t); }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_TREE) {
            if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
                log("!! 授权取消。再点②会自动换下一个零宽变体。");
                return;
            }
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable t) { log("!! 持久化授权失败: " + t); }
            prefs.edit().putString(KEY_TREE, tree.toString()).apply();
            treeOk = true;
            log("✓ 已授权: " + tree);
            try {
                log("  tree docId: " + show(DocumentsContract.getTreeDocumentId(tree)));
            } catch (Throwable ignored) { }
            updateSteps();
            run(new Runnable() { @Override public void run() { auto(); } });
            return;
        }
        if (req == REQ_JAR) {
            if (result != Activity.RESULT_OK || data == null) {
                log("!! 未选择 jar");
                return;
            }
            selectedUris.clear();
            selectedNames.clear();
            ClipData cd = data.getClipData();
            if (cd != null && cd.getItemCount() > 0) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u == null) continue;
                    try {
                        getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignored) { }
                    selectedUris.add(u);
                    selectedNames.add(queryName(u));
                }
            } else if (data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) { }
                selectedUris.add(u);
                selectedNames.add(queryName(u));
            }
            if (selectedUris.isEmpty()) {
                log("!! 未选择任何 jar");
                updateSelectedView();
                updateSteps();
                return;
            }
            persistSelected();
            log("已选择 " + selectedUris.size() + " 个 jar：");
            for (int i = 0; i < selectedNames.size(); i++)
                log("  • " + (selectedNames.get(i) == null ? selectedUris.get(i).toString() : selectedNames.get(i)));
            updateSelectedView();
            updateSteps();
        }
    }

    private String queryName(Uri u) {
        try {
            android.database.Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) return c.getString(idx);
                } finally { c.close(); }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    /** 把零宽字符显示成 <U+XXXX>，便于日志辨认 */
    private String show(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            boolean ig = false;
            for (String s : IGN) if (s.length() == 1 && s.charAt(0) == ch) { ig = true; break; }
            sb.append(ig ? String.format("<U+%04X>", (int) ch) : ch);
        }
        return sb.toString();
    }

    private void log(String s) {
        final String line = "[" + fmt.format(new Date()) + "] " + s;
        synchronized (logBuf) { logBuf.append(line).append('\n'); }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                logView.append(line + "\n");
                logScroll.post(new Runnable() {
                    @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private void setStatus(final String s, final int kind) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                statusView.setText(s);
                statusView.setBackgroundColor(kind == 1 ? C_GREEN
                        : kind == 2 ? C_RED
                        : kind == 3 ? C_ORANGE
                        : C_BLUE);
            }
        });
    }

    private void setBusy(final boolean b) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                progressBar.setVisibility(b ? View.VISIBLE : View.GONE);
                importBtn.setEnabled(b ? false : selectedUris.size() > 0);
            }
        });
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); }
        });
    }

    private void run(final Runnable r) {
        busy = true;
        setBusy(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try { r.run(); }
                catch (Throwable t) { log("!! 线程异常: " + t); }
                finally { busy = false; setBusy(false); }
            }
        }, "ts9-work").start();
    }

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { return Environment.isExternalStorageManager(); }
            catch (Throwable t) { return false; }
        }
        return true;
    }

    private Uri savedTree() {
        String s = prefs.getString(KEY_TREE, null);
        return s == null ? null : Uri.parse(s);
    }

    private List<File> roots() {
        List<File> l = new ArrayList<File>();
        addRoot(l, Environment.getExternalStorageDirectory());
        addRoot(l, new File("/storage/emulated/0"));
        addRoot(l, new File("/sdcard"));
        return l;
    }

    private void addRoot(List<File> l, File f) {
        if (f == null) return;
        for (File x : l) if (x.getAbsolutePath().equals(f.getAbsolutePath())) return;
        l.add(f);
    }

    private byte[] testData() {
        byte[] d = new byte[1024];
        for (int i = 0; i < d.length; i++) d[i] = (byte) (i & 0xFF);
        return d;
    }

    // ---------------------------------------------------------------- 通道 1：真实路径 File API

    /** v8 bug：先判 isDirectory()/mkdirs()，失败就主动抛异常，根本没尝试创建文件。这里直接写。 */
    private String writeFile(byte[] data, String fileName) {
        String lastErr = "无可用根目录";
        for (File root : roots()) {
            File jars = new File(root, REL);
            File dst = new File(jars, fileName);
            try {
                FileOutputStream o = new FileOutputStream(dst);
                try { o.write(data); o.flush(); } finally { o.close(); }
                if (!dst.isFile() || dst.length() != data.length)
                    throw new Exception("写入后大小不符");
                return null;
            } catch (Throwable t) {
                lastErr = t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " [dir=" + jars.isDirectory() + " exists=" + jars.exists() + "]";
            }
        }
        return lastErr;
    }

    private String probeFile() {
        String err = writeFile(testData(), TEST_NAME);
        if (err == null) {
            for (File root : roots()) {
                try { new File(new File(root, REL), TEST_NAME).delete(); }
                catch (Throwable ignored) { }
            }
        }
        return err;
    }

    // ---------------------------------------------------------------- 通道 2：SAF / ContentResolver

    /** 基于 tree 的真实 docId 拼目标，保证前缀与授权一致（v8 硬编码 DOC_ID 的 bug） */
    private List<String> safTargets(Uri tree) {
        List<String> l = new ArrayList<String>();
        String d = DocumentsContract.getTreeDocumentId(tree);
        l.add(d + "/" + TARGET_PKG + "/files/jars");
        l.add(d + "/data/" + TARGET_PKG + "/files/jars");
        return l;
    }

    private String writeSaf(Uri tree, byte[] data, String fileName) {
        lastSafDocId = null;
        String lastErr = "无可用 docId";
        for (String target : safTargets(tree)) {
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, target);
                try {
                    DocumentsContract.deleteDocument(getContentResolver(),
                            DocumentsContract.buildDocumentUriUsingTree(tree, target + "/" + fileName));
                } catch (Throwable ignored) { }
                Uri created = DocumentsContract.createDocument(
                        getContentResolver(), parent, "application/octet-stream", fileName);
                if (created == null) throw new Exception("createDocument 返回 null");
                OutputStream os = getContentResolver().openOutputStream(created);
                if (os == null) throw new Exception("openOutputStream 返回 null");
                try { os.write(data); os.flush(); } finally { os.close(); }
                lastSafDocId = target;
                log("  SAF 落点: " + show(target + "/" + fileName));
                return null;
            } catch (Throwable t) {
                lastErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        return lastErr;
    }

    private String probeSaf(Uri tree) {
        String err = writeSaf(tree, testData(), TEST_NAME);
        if (err == null && lastSafDocId != null) {
            try {
                DocumentsContract.deleteDocument(getContentResolver(),
                        DocumentsContract.buildDocumentUriUsingTree(tree,
                                lastSafDocId + "/" + TEST_NAME));
            } catch (Throwable ignored) { }
        }
        return err;
    }

    /** 通道 0：不经授权框，直接构造零宽 tree URI 交给系统 provider 代劳 */
    private String probeSafDirect() {
        String lastErr = "直连不可用";
        for (String[] v : SAF_VARIANTS) {
            Uri created = null;
            try {
                Uri tree = DocumentsContract.buildTreeDocumentUri(ES_AUTHORITY, v[0]);
                String target = v[0] + v[1] + TARGET_PKG + "/files/jars";
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, target);
                created = DocumentsContract.createDocument(
                        getContentResolver(), parent, "application/octet-stream", TEST_NAME);
                if (created == null) continue;
                OutputStream os = getContentResolver().openOutputStream(created);
                if (os == null) continue;
                try { os.write(testData()); os.flush(); } finally { os.close(); }
                try { DocumentsContract.deleteDocument(getContentResolver(), created); }
                catch (Throwable ignored) { }
                log("  直连成功 docId: " + show(target));
                prefs.edit().putString(KEY_TREE, tree.toString()).apply();
                return null;
            } catch (Throwable t) {
                lastErr = t.getClass().getSimpleName() + ": " + t.getMessage();
                if (created != null) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), created); }
                    catch (Throwable ignored) { }
                }
            }
        }
        return lastErr;
    }

    // ---------------------------------------------------------------- 通道 3：零宽路径 File API

    /** v8 bug：用 jars.list() 判断，应改用 base.list()（v7 已证明能列出 176 个子项） */
    private String writeZeroWidth(byte[] data, String fileName) {
        String lastErr = "无可用变体";
        int tried = 0;
        for (File root : roots()) {
            for (int i = 0; i < IGN.length && tried < 8; i++) {
                String[] vs = {
                        "Android/" + IGN[i] + "data",
                        "Android/da" + IGN[i] + "ta",
                        "Android/data" + IGN[i],
                        "A" + IGN[i] + "ndroid/data"
                };
                for (String v : vs) {
                    if (tried >= 8) break;
                    File base = new File(root, v);
                    String[] names = base.list();
                    if (names == null || names.length < 3) continue;
                    tried++;
                    File jars = new File(new File(new File(base, TARGET_PKG), "files"), "jars");
                    File dst = new File(jars, fileName);
                    try {
                        FileOutputStream o = new FileOutputStream(dst);
                        try { o.write(data); o.flush(); } finally { o.close(); }
                        if (!dst.isFile() || dst.length() != data.length)
                            throw new Exception("写入后大小不符");
                        return null;
                    } catch (Throwable t) {
                        lastErr = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
            }
            if (tried >= 8) break;
        }
        return lastErr;
    }

    private String probeZeroWidth() {
        String err = writeZeroWidth(testData(), TEST_NAME);
        if (err == null) {
            for (File root : roots()) {
                for (int i = 0; i < IGN.length; i++) {
                    String[] vs = {"Android/" + IGN[i] + "data", "Android/da" + IGN[i] + "ta",
                            "Android/data" + IGN[i], "A" + IGN[i] + "ndroid/data"};
                    for (String v : vs) {
                        try {
                            new File(new File(new File(new File(
                                    new File(root, v), TARGET_PKG), "files"), "jars"), TEST_NAME).delete();
                        } catch (Throwable ignored) { }
                    }
                }
            }
        }
        return err;
    }

    // ---------------------------------------------------------------- 自动检测 / 导入

    private void auto() {
        setStatus("正在检测…", 0);
        refreshStateFlags();
        log("");
        log("=== 自动检测 ===");
        boolean af = hasAllFiles();
        afOk = af;
        log("① 所有文件访问权限: " + (af ? "已开启 ✓" : "未开启 ✗"));
        if (!af) {
            log("   点① → 设置里打开开关 → 返回即可");
            setStatus("请点①开启『所有文件访问权限』", 3);
            updateSteps();
            return;
        }

        String e0 = probeSafDirect();
        if (e0 == null) {
            log("② ContentResolver 直连: 可用 ✓（无需任何授权框）");
            setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
            updateSteps();
            return;
        }
        log("② ContentResolver 直连: " + e0);

        String e1 = probeFile();
        if (e1 == null) {
            log("③ 真实路径直写: 可用 ✓");
            setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
            updateSteps();
            return;
        }
        log("③ 真实路径直写: 不可用 (" + e1 + ")");

        Uri tree = savedTree();
        if (tree != null) {
            treeOk = true;
            String e2 = probeSaf(tree);
            if (e2 == null) {
                log("④ 目录授权写入: 可用 ✓");
                setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
                updateSteps();
                return;
            }
            log("④ 目录授权写入: 不可用 (" + e2 + ")");
        } else {
            treeOk = false;
            log("④ 目录授权: 尚未授权");
        }

        String e3 = probeZeroWidth();
        if (e3 == null) {
            log("⑤ 零宽路径写入: 可用 ✓");
            setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
            updateSteps();
            return;
        }
        log("⑤ 零宽路径写入: 不可用 (" + e3 + ")");

        log("");
        log("=> 点②【一键授权】：用零宽 docId 打开选择器，按钮应当可点。");
        log("   选好后点『使用此文件夹』→『允许』。若仍灰，返回再点②换变体。");
        setStatus("请点②授权（零宽 URI）", 3);
        updateSteps();
    }

    /** 依次尝试三条通道写入单个文件，返回通道名或 null */
    private String tryWrite(byte[] data, String name) {
        String e1 = writeFile(data, name);
        if (e1 == null) return "真实路径直写";
        log("  通道1 失败: " + e1);
        Uri tree = savedTree();
        if (tree != null) {
            String e2 = writeSaf(tree, data, name);
            if (e2 == null) return "目录授权 (SAF)";
            log("  通道2 失败: " + e2);
        } else {
            log("  通道2 跳过: 未授权目录（点②）");
        }
        String e3 = writeZeroWidth(data, name);
        if (e3 == null) return "零宽路径";
        log("  通道3 失败: " + e3);
        return null;
    }

    /** 批量导入：逐个读取已选 jar，写入目标目录，统计成功/失败 */
    private void doImportAll() {
        setStatus("正在导入…", 0);
        log("");
        log("=== 开始批量导入 ===");
        if (selectedUris.isEmpty()) {
            log("!! 尚未选择 jar，请先点③选择");
            setStatus("✗ 未选择文件", 2);
            return;
        }
        String prefix = prefixEdit.getText().toString().trim();
        int n = selectedUris.size();
        int ok = 0, fail = 0;
        for (int i = 0; i < n; i++) {
            Uri u = selectedUris.get(i);
            String base = selectedNames.get(i);
            if (base == null || base.isEmpty()) base = "source_" + (i + 1) + ".jar";
            String target = prefix.isEmpty() ? base : (prefix + base);
            if (!target.toLowerCase(Locale.US).endsWith(".jar")) target = target + ".jar";
            log("[" + (i + 1) + "/" + n + "] " + base + " → " + target);
            byte[] data;
            try {
                InputStream in = getContentResolver().openInputStream(u);
                if (in == null) throw new Exception("无法读取源 jar");
                data = readAll(in);
                if (data.length == 0) throw new Exception("源 jar 为空");
            } catch (Throwable t) {
                fail++;
                log("  ✗ 读取失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                continue;
            }
            String channel = tryWrite(data, target);
            if (channel != null) {
                ok++;
                log("  ✓ 通道: " + channel);
            } else {
                fail++;
                log("  ✗ 写入失败: 三条通道均失败");
            }
        }
        log("");
        log("=== 完成：成功 " + ok + " / 失败 " + fail + " （共 " + n + "）===");
        if (fail == 0) {
            log("请彻底清后台重启『我的听书』，让它扫描 jars/ 加载新源。");
            setStatus("✓ 导入 " + ok + " 个成功，请重启『我的听书』", 1);
            toast("导入成功 " + ok + " 个，请重启「我的听书」");
        } else if (ok > 0) {
            setStatus("⚠ 部分成功：" + ok + " 成功 / " + fail + " 失败", 3);
        } else {
            setStatus("✗ 全部失败，见日志", 2);
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[16384];
            int nn;
            while ((nn = in.read(b)) > 0) bo.write(b, 0, nn);
        } finally { try { in.close(); } catch (Throwable ignored) { } }
        return bo.toByteArray();
    }
}
