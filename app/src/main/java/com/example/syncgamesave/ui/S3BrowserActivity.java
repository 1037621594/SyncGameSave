package com.example.syncgamesave.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.storage.PathRules;
import com.example.syncgamesave.sync.RemoteFileDeletion;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Locale;

public class S3BrowserActivity extends AppCompatActivity {
    public static final String EXTRA_SELECT_MODE = "select_mode";
    public static final String EXTRA_PREFIX = "prefix";
    public static final String RESULT_PREFIX = "selected_prefix";

    private S3BrowserModel model;
    private TextInputEditText prefix;
    private TextView status;
    private LinearLayout listing;
    private Button refresh;
    private Button up;
    private Button root;
    private Button choose;
    private Button more;
    private boolean selectMode;
    private AlertDialog fileDialog;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        selectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);
        model = new ViewModelProvider(this).get(S3BrowserModel.class);
        LinearLayout body = Ui.screen(this, selectMode ? "选择 S3 目录" : "浏览 S3 文件");
        Ui.text(body, selectMode ? "点击目录进入，选择当前目录后自动填入路径映射。"
                : "点击目录进入，点击文件名查看详情。删除只影响云端；本地文件保留。", 14);
        prefix = Ui.field(body, "当前 S3 目录（留空为 Bucket 根目录）", "", false);
        refresh = Ui.button(body, "跳转／刷新", () -> model.refresh(Ui.value(prefix)));
        up = Ui.button(body, "上一级", model::up);
        root = Ui.button(body, "返回根目录", () -> model.enter(""));
        if (selectMode) choose = Ui.button(body, "选择当前目录", this::selectCurrent);
        status = Ui.text(body, "正在加载……", 14);
        listing = new LinearLayout(this);
        listing.setOrientation(LinearLayout.VERTICAL);
        body.addView(listing, new LinearLayout.LayoutParams(-1, -2));
        more = Ui.button(body, "加载更多", model::loadMore);
        model.state().observe(this, this::render);
        model.initialize(state == null ? getIntent().getStringExtra(EXTRA_PREFIX) : state.getString("directory"));
    }

    private void render(S3BrowserModel.State state) {
        prefix.setText(state.getPrefix());
        boolean enabled = !state.isLoading();
        prefix.setEnabled(enabled);
        refresh.setEnabled(enabled);
        up.setEnabled(enabled && !state.getPrefix().isEmpty());
        root.setEnabled(enabled && !state.getPrefix().isEmpty());
        if (choose != null) choose.setEnabled(enabled && state.isLoaded() && !state.getPrefix().isEmpty());
        more.setEnabled(enabled);
        more.setVisibility(state.getNextMarker().isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        String summary = "已加载子目录 " + state.getDirectories().size() + " 个，文件 " + state.getFiles().size() + " 个";
        if (!state.getMessage().isEmpty()) summary += "\n" + state.getMessage();
        if (!state.getError().isEmpty()) summary += "\n" + state.getError();
        status.setText(summary);
        listing.removeAllViews();
        for (String directory : state.getDirectories()) {
            if (selectMode && RemoteFileDeletion.isMetadata(directory)) continue;
            Ui.button(listing, "目录：" + directory.substring(state.getPrefix().length()), () -> model.enter(directory))
                    .setEnabled(enabled);
        }
        for (RemoteFile file : state.getFiles()) addFile(state.getPrefix(), file, enabled);
        if (state.isLoaded() && state.getDirectories().isEmpty() && state.getFiles().isEmpty())
            Ui.text(listing, "当前目录为空", 14);
    }

    private void addFile(String directory, RemoteFile file, boolean enabled) {
        String name = file.getKey().substring(directory.length());
        Button details = Ui.button(listing, "文件：" + name + "\n" + size(file.getSize()), () -> showFile(file));
        details.setAllCaps(false);
        details.setEnabled(enabled);
        if (!selectMode && !RemoteFileDeletion.isMetadata(file.getKey()))
            Ui.button(listing, "删除 " + name, () -> confirmDelete(file)).setEnabled(enabled);
    }

    private void showFile(RemoteFile file) {
        fileDialog = new MaterialAlertDialogBuilder(this).setTitle("S3 文件详情")
                .setMessage("路径：" + file.getKey() + "\n大小：" + file.getSize() + " 字节"
                        + "\n修改时间：" + Ui.time(file.getUploadedAt())
                        + "\nETag：" + (file.getHash() == null ? "未知" : file.getHash()))
                .setPositiveButton("关闭", null).show();
    }

    private void confirmDelete(RemoteFile file) {
        fileDialog = new MaterialAlertDialogBuilder(this).setTitle("删除远端文件？")
                .setMessage(file.getKey() + "\n\n仅删除云端文件并更新同步记录，本地文件保留。再次上传时，仍存在的本地文件会重新上传。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> model.delete(file)).show();
    }

    private void selectCurrent() {
        S3BrowserModel.State state = model.state().getValue();
        if (state == null || state.isLoading() || !state.isLoaded()) return;
        try {
            String selected = state.getPrefix();
            if (!Ui.value(prefix).equals(selected))
                throw new IllegalArgumentException("目录已修改，请先点击跳转／刷新再选择");
            if (RemoteFileDeletion.isMetadata(selected))
                throw new IllegalArgumentException("同步元数据目录不能用作路径映射");
            if (!PathRules.prefix(selected).equals(selected))
                throw new IllegalArgumentException("此 S3 目录名称不符合路径映射规则，请选择其他目录");
            setResult(RESULT_OK, new Intent().putExtra(RESULT_PREFIX, selected));
            finish();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String size(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        if (value < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GiB", value / (1024.0 * 1024 * 1024));
    }

    @Override public void onBackPressed() {
        S3BrowserModel.State state = model.state().getValue();
        if (state != null && !state.isLoading() && !state.getPrefix().isEmpty()) model.up();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        S3BrowserModel.State current = model.state().getValue();
        if (current != null) state.putString("directory", current.getPrefix());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (fileDialog != null) fileDialog.dismiss();
        super.onDestroy();
    }
}
