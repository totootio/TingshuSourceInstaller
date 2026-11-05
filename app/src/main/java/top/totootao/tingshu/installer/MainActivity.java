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
 * 听书源导入器 v9 —— 零宽字符 docId 的 SAF 授权。
 *
 * 依据（lbchs5343/Android-FileExplorerDemo，2024-05-18 更新）：
 *   Android 13/14 上可用零宽字符插入 docId 来直接授权 Android/data：
 *     content://com.android.externalstorage.documents
 *       /tree/primary%3AA%E2%80%8Bndroid%2Fdata
 *       /document/primary%3AA%E2%80%8Bndroid%2Fdata
 *   DocumentsUI 的字符串黑名单不认这个变体，「使用此文件夹」不再变灰；
 *   ExternalStorageProvider 归一化后仍映射到真实 Android/data。
 *
 * 用户 v7 日志已证明：该机零宽路径能列出真实 Android/data 的 176 个包名子项，
 * 即可忽略码点归一化在这台 Xiaomi Android 15 上确实生效。
 *
 * 通道：
 *   0. ContentResolver 直连 ExternalStorageProvider（无需任何授权框）
 *   1. 真实路径 File API 直写（修掉 v8「先判目录、失败就放弃、根本没试写文件」的 bug）
 *   2. SAF tree URI 写入（零宽 docId 授权后，由系统 provider 代劳 create）
 *   3. 零宽路径 File API 兜底（修掉 v8 判断条件写错的 bug）
 *
 * 不依赖 Shizuku / root / 开发者选项 / 无线调试；不自动重启进程（v8.0 白屏原因）。
 */
public class MainActivity extends Activity {

    private static final String PREF = "ts9";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_JAR = "jar_uri";
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
    private static final int CODE_LOG = 4;

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
    private TextView logView;
    private EditText nameEdit;
    private ScrollView logScroll;
    private final StringBuilder logBuf = new StringBuilder();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private volatile boolean busy = false;
    private boolean suppressAuto = false;
    private volatile String lastSafDocId = null;
    private String cachedName = "sources_by_custom.jar";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        setContentView(buildUi());

        log("=== 听书源导入器 v9.0（零宽 docId 授权）===");
        log("设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        log("目标: /storage/emulated/0/" + REL + "/");
        log("");
        log("① 所有文件访问权限已开就够；写不进再点②。");
        log("② 本版用「零宽字符 docId」打开选择器，系统不会把它识别成");
        log("   Android/data，『使用此文件夹』应当可以点。");
        log("不需要 Shizuku / root / 开发者选项 / 无线调试，也不会自动重启。");
        cleanupPendingRestart();
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

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F5F5"));
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(17);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(10), dp(14), dp(10), dp(14));
        statusView.setTextColor(Color.WHITE);
        statusView.setBackgroundColor(Color.parseColor("#607D8B"));
        statusView.setText("正在检测…");
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nameLabel = new TextView(this);
        nameLabel.setText("导入后的文件名（选完 jar 会自动填入）:");
        nameLabel.setTextSize(12);
        nameLabel.setTextColor(Color.parseColor("#444444"));
        nameLabel.setPadding(0, dp(8), 0, 0);
        root.addView(nameLabel);

        nameEdit = new EditText(this);
        nameEdit.setText(cachedName);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEdit.setTextSize(14);
        root.addView(nameEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.addView(button("① 开启『所有文件访问权限』", CODE_ALLFILES));
        btns.addView(button("② 一键授权（零宽 URI，灰了就再点换变体）", CODE_TREE));
        btns.addView(button("③ 选择 jar（选完自动导入）", CODE_PICK));
        btns.addView(button("④ 复制日志", CODE_LOG));
        root.addView(btns);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#1E1E1E"));
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.parseColor("#D6D6D6"));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(logScroll, lp);
        return root;
    }

    private Button button(String text, final int code) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(code); }
        });
        return b;
    }

    private void dispatch(final int code) {
        if (busy && code != CODE_LOG) { toast("正在执行，请稍候"); return; }
        cachedName = nameEdit.getText().toString().trim();
        if (cachedName.length() == 0) cachedName = "sources_by_custom.jar";
        switch (code) {
            case CODE_ALLFILES: openAllFilesSettings(); break;
            case CODE_TREE:     requestTree(); break;
            case CODE_PICK:     pickJar(); break;
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

    private void pickJar() {
        suppressAuto = true;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
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
            log("✓ 已授权: " + tree);
            try {
                log("  tree docId: " + show(DocumentsContract.getTreeDocumentId(tree)));
            } catch (Throwable ignored) { }
            run(new Runnable() { @Override public void run() { auto(); } });
            return;
        }
        if (req == REQ_JAR) {
            if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
                log("!! 未选择 jar");
                return;
            }
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) { }
            prefs.edit().putString(KEY_JAR, u.toString()).apply();
            String n = queryName(u);
            if (n != null && n.toLowerCase(Locale.US).endsWith(".jar")) {
                cachedName = n;
                nameEdit.setText(n);
            }
            log("已选择 jar: " + (n == null ? u.toString() : n));
            run(new Runnable() { @Override public void run() { doImport(); } });
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
                statusView.setBackgroundColor(kind == 1 ? Color.parseColor("#2E7D32")
                        : kind == 2 ? Color.parseColor("#C62828")
                        : kind == 3 ? Color.parseColor("#EF6C00")
                        : Color.parseColor("#607D8B"));
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
        new Thread(new Runnable() {
            @Override public void run() {
                try { r.run(); }
                catch (Throwable t) { log("!! 线程异常: " + t); }
                finally { busy = false; }
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
        log("");
        log("=== 自动检测 ===");
        boolean af = hasAllFiles();
        log("① 所有文件访问权限: " + (af ? "已开启 ✓" : "未开启 ✗"));
        if (!af) {
            log("   点① → 设置里打开开关 → 返回即可");
            setStatus("请点①开启『所有文件访问权限』", 3);
            return;
        }

        String e0 = probeSafDirect();
        if (e0 == null) {
            log("② ContentResolver 直连: 可用 ✓（无需任何授权框）");
            setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
            return;
        }
        log("② ContentResolver 直连: " + e0);

        String e1 = probeFile();
        if (e1 == null) {
            log("③ 真实路径直写: 可用 ✓");
            setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
            return;
        }
        log("③ 真实路径直写: 不可用 (" + e1 + ")");

        Uri tree = savedTree();
        if (tree != null) {
            String e2 = probeSaf(tree);
            if (e2 == null) {
                log("④ 目录授权写入: 可用 ✓");
                setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
                return;
            }
            log("④ 目录授权写入: 不可用 (" + e2 + ")");
        } else {
            log("④ 目录授权: 尚未授权");
        }

        String e3 = probeZeroWidth();
        if (e3 == null) {
            log("⑤ 零宽路径写入: 可用 ✓");
            setStatus("✓ 可写入 → 点③选 jar 即自动导入", 1);
            return;
        }
        log("⑤ 零宽路径写入: 不可用 (" + e3 + ")");

        log("");
        log("=> 点②【一键授权】：用零宽 docId 打开选择器，按钮应当可点。");
        log("   选好后点『使用此文件夹』→『允许』。若仍灰，返回再点②换变体。");
        setStatus("请点②授权（零宽 URI）", 3);
    }

    private void doImport() {
        setStatus("正在导入…", 0);
        log("");
        log("=== 开始导入 ===");
        try {
            String jarStr = prefs.getString(KEY_JAR, null);
            if (jarStr == null) throw new Exception("尚未选择 jar，请先点③");
            InputStream in = getContentResolver().openInputStream(Uri.parse(jarStr));
            if (in == null) throw new Exception("无法读取源 jar，请重新点③选择");
            byte[] data = readAll(in);
            if (data.length == 0) throw new Exception("源 jar 为空");
            log("源 jar: " + data.length + " 字节 → " + cachedName);

            String channel = null;
            String e1 = writeFile(data, cachedName);
            if (e1 == null) {
                channel = "真实路径直写";
            } else {
                log("  通道1 失败: " + e1);
                Uri tree = savedTree();
                if (tree != null) {
                    String e2 = writeSaf(tree, data, cachedName);
                    if (e2 == null) channel = "目录授权 (SAF)";
                    else log("  通道2 失败: " + e2);
                } else {
                    log("  通道2 跳过: 未授权目录（点②）");
                }
                if (channel == null) {
                    String e3 = writeZeroWidth(data, cachedName);
                    if (e3 == null) channel = "零宽路径";
                    else log("  通道3 失败: " + e3);
                }
            }

            if (channel == null) throw new Exception("三条通道均失败，请先点②完成授权，再看诊断日志");
            log("");
            log("✓ 导入完成  通道: " + channel);
            log("  目标: /storage/emulated/0/" + REL + "/" + cachedName);
            log("请彻底清后台重启『我的听书』，让它扫描 jars/ 加载新源。");
            setStatus("✓ 导入成功，请重启『我的听书』", 1);
            toast("导入成功，请重启「我的听书」");
        } catch (Throwable t) {
            log("!! 导入失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            setStatus("✗ 导入失败，见日志", 2);
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        } finally { try { in.close(); } catch (Throwable ignored) { } }
        return bo.toByteArray();
    }
}
