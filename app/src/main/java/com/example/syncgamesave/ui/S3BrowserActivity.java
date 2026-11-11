package com.example.syncgamesave.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.syncgamesave.SyncApplication;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.model.S3Config;
import com.example.syncgamesave.storage.PathRules;
import com.example.syncgamesave.storage.S3Client;
import com.example.syncgamesave.sync.CancellationToken;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class S3BrowserActivity extends AppCompatActivity {
    public static final String EXTRA_SELECT_MODE = "select_mode";
    public static final String EXTRA_PREFIX = "prefix";
    public static final String RESULT_PREFIX = "selected_prefix";

    private SyncApplication app;
    private TextInputEditText prefix;
    private TextView status;
    private LinearLayout listing;
    private boolean selectMode;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile CancellationToken activeToken;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (SyncApplication) getApplication();
        selectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);
        LinearLayout body = Ui.screen(this, selectMode ? "选择 S3 目录" : "浏览 S3 文件");
        Ui.text(body, selectMode
                ? "进入已有对象所在的目录后，可以将当前目录返回到路径映射。"
                : "按目录前缀浏览当前 Bucket 中的对象，可删除远端文件。", 14);
        prefix = Ui.field(body, "S3 目录前缀，例如 saves/device-a/",
                getIntent().getStringExtra(EXTRA_PREFIX), false);
        Ui.button(body, "刷新列表", this::load);
        if (selectMode) Ui.button(body, "选择当前目录", this::selectCurrent);
        status = Ui.text(body, "尚未加载", 14);
        listing = new LinearLayout(this);
        listing.setOrientation(LinearLayout.VERTICAL);
        body.addView(listing, new LinearLayout.LayoutParams(-1, -2));
        load();
    }

    private void load() {
        if (activeToken != null) {
            toast("正在读取 S3，请稍候");
            return;
        }
        final String current;
        try {
            current = normalizePrefix(Ui.value(prefix));
        } catch (Exception e) {
            toast(e.getMessage() == null ? "S3 目录前缀不合法" : e.getMessage());
            return;
        }
        prefix.setText(current);
        listing.removeAllViews();
        status.setText("正在读取 S3 文件列表……");
        final CancellationToken token = new CancellationToken();
        activeToken = token;
        worker.execute(() -> {
            try {
                S3Config config = app.configStore().read();
                S3Client.validate(config);
                List<RemoteFile> files;
                try (S3Client client = new S3Client(config, token)) {
                    files = client.list(current);
                }
                final List<RemoteFile> result = files;
                runOnUiThread(() -> render(current, result));
            } catch (Exception e) {
                runOnUiThread(() -> showError("读取 S3 文件失败", e));
            } finally {
                activeToken = null;
            }
        });
    }

    private void render(String current, List<RemoteFile> files) {
        listing.removeAllViews();
        Set<String> directories = new TreeSet<>();
        List<RemoteFile> directFiles = new ArrayList<>();
        for (RemoteFile file : files) {
            String key = file.getKey();
            if (key == null || !key.startsWith(current)) continue;
            String relative = key.substring(current.length());
            if (relative.isEmpty()) continue;
            int slash = relative.indexOf('/');
            if (slash >= 0) directories.add(current + relative.substring(0, slash + 1));
            else directFiles.add(file);
        }
        Collections.sort(directFiles, Comparator.comparing(RemoteFile::getKey, String.CASE_INSENSITIVE_ORDER));
        status.setText("当前目录对象数：" + directFiles.size() + "，子目录数：" + directories.size());
        for (String directory : directories) {
            String label = directory.substring(current.length());
            Ui.button(listing, "进入目录：" + label, () -> {
                prefix.setText(directory);
                load();
            });
        }
        for (RemoteFile file : directFiles) addFile(file);
        if (directories.isEmpty() && directFiles.isEmpty()) Ui.text(listing, "当前目录没有对象", 14);
    }

    private void addFile(RemoteFile file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(this);
        label.setText(file.getKey() + "\n" + size(file.getSize()) + "，上传时间：" + Ui.time(file.getUploadedAt()));
        label.setTextIsSelectable(true);
        label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.setMaxLines(2);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton remove = new MaterialButton(this);
        remove.setText("删除");
        remove.setOnClickListener(view -> confirmDelete(file));
        row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
        listing.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void confirmDelete(RemoteFile file) {
        if (app.controller().isBusy()) {
            toast("同步处理中，请稍后删除");
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle("删除远端文件？")
                .setMessage(file.getKey() + "\n删除后无法通过此应用恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> delete(file)).show();
    }

    private void delete(RemoteFile file) {
        if (activeToken != null) {
            toast("正在读取 S3，请稍候");
            return;
        }
        if (app.controller().isBusy()) {
            toast("同步处理中，请稍后删除");
            return;
        }
        status.setText("正在删除：" + file.getKey());
        final CancellationToken token = new CancellationToken();
        activeToken = token;
        worker.execute(() -> {
            try {
                S3Config config = app.configStore().read();
                S3Client.validate(config);
                try (S3Client client = new S3Client(config, token)) {
                    client.delete(file.getKey());
                }
                runOnUiThread(() -> {
                    toast("已删除：" + file.getKey());
                    load();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("删除 S3 文件失败", e));
            } finally {
                activeToken = null;
            }
        });
    }

    private void selectCurrent() {
        try {
            String selected = normalizePrefix(Ui.value(prefix));
            if (selected.isEmpty()) {
                toast("请先进入要映射的 S3 目录");
                return;
            }
            setResult(RESULT_OK, new Intent().putExtra(RESULT_PREFIX, selected));
            finish();
        } catch (Exception e) {
            toast(e.getMessage() == null ? "S3 目录前缀不合法" : e.getMessage());
        }
    }

    private static String normalizePrefix(String input) {
        String value = input == null ? "" : input.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";
        PathRules.validateRelative(value);
        return value + "/";
    }

    private static String size(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", value / 1024.0);
        if (value < 1024 * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.1f GiB", value / (1024.0 * 1024 * 1024));
    }

    private void showError(String title, Exception error) {
        if (isFinishing()) return;
        String message = error.getMessage() == null ? "请检查 S3 配置、网络和访问权限" : error.getMessage();
        new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
                .setPositiveButton("知道了", null).show();
    }

    @Override protected void onDestroy() {
        CancellationToken token = activeToken;
        if (token != null) token.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void toast(String text) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show(); }
}
