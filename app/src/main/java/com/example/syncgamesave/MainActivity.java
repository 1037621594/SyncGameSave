package com.example.syncgamesave;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.example.syncgamesave.model.ConflictRequest;
import com.example.syncgamesave.model.PathMapping;
import com.example.syncgamesave.model.SyncResult;
import com.example.syncgamesave.model.TaskStatus;
import com.example.syncgamesave.sync.SyncController;
import com.example.syncgamesave.ui.MappingEditorActivity;
import com.example.syncgamesave.ui.S3ConfigActivity;
import com.example.syncgamesave.ui.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private SyncApplication app;
    private SyncController controller;
    private DrawerLayout drawer;
    private LinearLayout container;
    private AlertDialog conflictDialog;
    private String visibleConflict;
    private String pendingUpload;
    private final ActivityResultLauncher<Intent> mappingEditor = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    pendingUpload = result.getData().getStringExtra(MappingEditorActivity.MAPPING_ID);
                    render();
                    uploadPending();
                }
            });
    private final ActivityResultLauncher<Intent> configEditor = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) controller.clearHistory();
                render();
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (SyncApplication) getApplication();
        controller = app.controller();
        if (state != null) pendingUpload = state.getString("pending_upload");
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.drawerLayout);
        container = findViewById(R.id.mappingContainer);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, toolbar, R.string.drawer_open, R.string.drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();
        NavigationView navigation = findViewById(R.id.navigationView);
        navigation.setNavigationItemSelectedListener(item -> {
            drawer.closeDrawer(GravityCompat.START);
            if (item.getItemId() == R.id.menu_add_path) editMapping(null);
            else if (item.getItemId() == R.id.menu_s3_config) editConfig();
            return false;
        });
        controller.changes().observe(this, revision -> { render(); uploadPending(); });
        controller.conflicts().observe(this, this::showConflict);
    }

    @Override protected void onResume() { super.onResume(); if (container != null) render(); }

    private void uploadPending() {
        if (pendingUpload == null || controller.isBusy()) return;
        String id = pendingUpload;
        pendingUpload = null;
        for (PathMapping mapping : app.repository().mappings()) if (mapping.getId().equals(id)) {
            controller.start(mapping, false);
            break;
        }
    }

    private void render() {
        if (container == null) return;
        container.removeAllViews();
        List<PathMapping> mappings = app.repository().mappings();
        Ui.text(container, "路径映射（" + mappings.size() + "）", 21);
        Ui.text(container, "保存路径后自动上传；之后可手动上传或下载。操作只传输文件，不传播删除。", 14);
        if (controller.startupError() != null) {
            Ui.text(container, controller.startupError(), 15);
            Button recover = Ui.button(container, "重新尝试恢复上次写入", controller::retryRecovery);
            recover.setEnabled(!controller.isBusy());
        }
        if (mappings.isEmpty()) {
            Ui.text(container, "尚未添加同步路径。先设置 S3 连接，再添加本地目录。", 16);
            Ui.button(container, "S3 配置", this::editConfig);
            Ui.button(container, "添加路径", () -> editMapping(null));
        }
        for (PathMapping mapping : mappings) addCard(mapping);
        boolean running = false;
        for (PathMapping mapping : mappings) {
            TaskStatus status = controller.status(mapping.getId());
            if (status != null && status.isRunning()) running = true;
        }
        if (controller.isBusy() && !running) Ui.text(container, "正在恢复同步状态，请稍候……", 14);
    }

    private void addCard(PathMapping mapping) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = Ui.dp(this, 16);
        container.addView(card, cardParams);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 14);
        body.setPadding(padding, padding, padding, padding);
        card.addView(body, new ViewGroup.LayoutParams(-1, -2));
        Ui.text(body, mapping.getName(), 19);
        Ui.text(body, "本地：" + mapping.getLocalLabel() + "\n远端：" + mapping.getRemotePrefix()
                + "\n格式：" + mapping.getFormats() + (mapping.isRecursive() ? "（含子目录）" : "（仅当前目录）")
                + "\n最后完整上传成功：" + Ui.time(mapping.getLastUploadTime()), 14);
        TaskStatus status = controller.status(mapping.getId());
        boolean running = status != null && status.isRunning();
        Ui.text(body, status == null ? mapping.getLastResult() : status.getMessage(), 14);
        if (running) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(status.getTotal() == 0);
            bar.setMax(Math.max(1, status.getTotal()));
            bar.setProgress(status.getDone());
            body.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            Ui.button(body, "取消任务", () -> {
                controller.cancel(mapping.getId());
                toast("正在取消；已经开始的安全替换将先完成或恢复");
            });
            return;
        }
        boolean enabled = !controller.isBusy();
        LinearLayout transfer = row(body);
        rowButton(transfer, "上传到云端", () -> start(mapping, false), enabled);
        rowButton(transfer, "从云端下载", () -> start(mapping, true), enabled);
        LinearLayout edit = row(body);
        rowButton(edit, "编辑", () -> editMapping(mapping), enabled);
        rowButton(edit, "删除配置", () -> delete(mapping), enabled);
        if (status != null && status.getResult() != null) {
            Ui.button(body, "查看同步明细", () -> details(status.getResult()));
            SyncResult result = status.getResult();
            if (result.getFailed() > 0 || result.isCancelled() || result.getDeferred() > 0) {
                Button retry = Ui.button(body, "重试上次操作", () -> start(mapping, "download".equals(result.getDirection())));
                retry.setEnabled(enabled);
            }
        } else if (mapping.getLastResult().contains("未完成") || mapping.getLastResult().contains("已取消")
                || mapping.getLastResult().matches("(?s).*失败 [1-9][0-9]*.*")) {
            Button retry = Ui.button(body, "重试上次操作", () -> start(mapping, "download".equals(mapping.getLastDirection())));
            retry.setEnabled(enabled);
        }
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void rowButton(LinearLayout row, String label, Runnable action, boolean enabled) {
        com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(this);
        button.setText(label);
        button.setEnabled(enabled);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        row.addView(button, params);
    }

    private void start(PathMapping mapping, boolean download) {
        if (!controller.start(mapping, download)) toast("已有同步任务，请完成后再操作");
    }

    private void editMapping(PathMapping mapping) {
        if (controller.isBusy()) { toast("请等待当前任务完成"); return; }
        Intent intent = new Intent(this, MappingEditorActivity.class);
        if (mapping != null) intent.putExtra(MappingEditorActivity.MAPPING_ID, mapping.getId());
        mappingEditor.launch(intent);
    }

    private void editConfig() {
        if (controller.isBusy()) { toast("请等待当前任务完成"); return; }
        configEditor.launch(new Intent(this, S3ConfigActivity.class));
    }

    private void delete(PathMapping mapping) {
        new MaterialAlertDialogBuilder(this).setTitle("删除“" + mapping.getName() + "”？")
                .setMessage("仅删除映射及本地同步记录，保留本地文件和云端对象。")
                .setNegativeButton("取消", null).setPositiveButton("删除配置", (dialog, which) -> {
                    if (controller.isBusy()) { toast("请等待任务完成"); return; }
                    app.repository().deleteMapping(mapping.getId());
                    controller.removeHistory(mapping.getId());
                    render();
                }).show();
    }

    private void details(SyncResult result) {
        StringBuilder message = new StringBuilder(result.summary());
        if (result.getMessages().isEmpty()) message.append("\n没有异常或冲突。");
        else for (String line : result.getMessages()) message.append("\n\n").append(line);
        new MaterialAlertDialogBuilder(this).setTitle("同步明细（最多 100 条）").setMessage(message.toString())
                .setPositiveButton("关闭", null).show();
    }

    private void showConflict(ConflictRequest request) {
        if (request == null) {
            if (conflictDialog != null) conflictDialog.dismiss();
            conflictDialog = null;
            visibleConflict = null;
            return;
        }
        if (request.getId().equals(visibleConflict) && conflictDialog != null && conflictDialog.isShowing()) return;
        if (conflictDialog != null) conflictDialog.dismiss();
        visibleConflict = request.getId();
        conflictDialog = new MaterialAlertDialogBuilder(this).setTitle("确认覆盖本地存档")
                .setMessage(request.getReason() + "\n\n文件：" + request.getPath()
                        + "\n本地更新时间：" + Ui.time(request.getLocalTime()) + "\n远端文件更新时间：" + Ui.time(request.getRemoteTime()))
                .setNegativeButton("跳过", (dialog, which) -> controller.answer(request.getId(), false))
                .setPositiveButton("仍然覆盖", (dialog, which) -> controller.answer(request.getId(), true))
                .setOnCancelListener(dialog -> controller.answer(request.getId(), false)).create();
        conflictDialog.setOnShowListener(dialog -> conflictDialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus());
        conflictDialog.show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending_upload", pendingUpload);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (conflictDialog != null) { conflictDialog.setOnCancelListener(null); conflictDialog.dismiss(); }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
        else super.onBackPressed();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}


