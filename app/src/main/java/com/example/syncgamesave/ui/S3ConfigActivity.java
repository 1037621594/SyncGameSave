package com.example.syncgamesave.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.example.syncgamesave.SyncApplication;
import com.example.syncgamesave.model.S3Config;
import com.example.syncgamesave.storage.S3Client;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import java.util.Objects;

public class S3ConfigActivity extends AppCompatActivity {
    @lombok.Getter
    @lombok.Setter
    public static class DraftModel extends ViewModel {
        S3Config draft;
        S3Config original;
    }

    private DraftModel model;
    private SyncApplication app;
    private TextInputEditText bucket;
    private TextInputEditText access;
    private TextInputEditText secret;
    private TextInputEditText endpoint;
    private TextInputEditText region;
    private TextInputEditText importContent;
    private CheckBox pathStyle;
    private final Gson gson = new Gson();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        app = (SyncApplication) getApplication();
        model = new ViewModelProvider(this).get(DraftModel.class);
        if (model.draft == null) {
            try { model.original = app.configStore().read(); model.draft = model.original; }
            catch (Exception e) {
                model.draft = new S3Config();
                new MaterialAlertDialogBuilder(this).setTitle("无法读取原凭证")
                        .setMessage("安全存储中的凭证无法解密。你可以重新填写并保存；保存会替换原配置并重置同步基线。")
                        .setPositiveButton("重新填写", null).show();
            }
        }
        S3Config config = model.draft;
        LinearLayout body = Ui.screen(this, "S3 配置");
        Ui.text(body, "填写存储服务提供的 S3 地址，支持 HTTP、HTTPS 和端口。七牛需使用 S3 服务地址，不能使用原上传地址或下载域名。", 14);
        endpoint = Ui.field(body, "S3 Endpoint，例如 http://s3.cn-east-1.qiniucs.com", config.getEndpoint(), false);
        region = Ui.field(body, "Region，例如 cn-east-1；以服务商配置为准", config.getRegion(), false);
        bucket = Ui.field(body, "Bucket（S3 空间名称）", config.getBucket(), false);
        access = Ui.field(body, "Access Key", config.getAccessKey(), true);
        secret = Ui.field(body, "Secret Key", config.getSecretKey(), true);
        pathStyle = new CheckBox(this);
        pathStyle.setText("路径式访问（Endpoint/Bucket，适用于 IP 和多数自建服务）");
        pathStyle.setChecked(config.isPathStyle());
        body.addView(pathStyle);
        Ui.text(body, "取消勾选时使用 Bucket.Endpoint 域名访问。首次切换 S3，或更换地址、区域、Bucket、访问方式时，将清除同步记录，下一次上传按首次同步处理。原本地目录与格式配置保留。", 14);
        Ui.text(body, "导入导出内容包含 Access Key 和 Secret Key，请只通过可信渠道传递，并在使用后清理剪切板。", 14);
        Ui.button(body, "导出配置到剪切板", this::exportConfig);
        importContent = Ui.field(body, "粘贴 S3 配置 JSON", "", false);
        Ui.button(body, "导入配置", this::importConfig);
        Ui.button(body, "保存 S3 配置", this::save);
    }

    private void exportConfig() {
        S3Config config = collect();
        model.draft = config;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "设备不支持剪切板", Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("S3 配置", gson.toJson(config)));
        Toast.makeText(this, "S3 配置已复制到剪切板", Toast.LENGTH_LONG).show();
    }

    private void importConfig() {
        String payload = Ui.value(importContent);
        if (payload.isEmpty()) {
            Toast.makeText(this, "请先粘贴 S3 配置 JSON", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            S3Config imported = gson.fromJson(payload, S3Config.class);
            if (imported == null) throw new IllegalArgumentException("配置内容为空");
            normalize(imported);
            S3Client.validate(imported);
            model.draft = imported;
            endpoint.setText(imported.getEndpoint());
            region.setText(imported.getRegion());
            bucket.setText(imported.getBucket());
            access.setText(imported.getAccessKey());
            secret.setText(imported.getSecretKey());
            pathStyle.setChecked(imported.isPathStyle());
            importContent.setText("");
            Toast.makeText(this, "配置已导入，请检查后点击保存", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            String message = e instanceof IllegalArgumentException && e.getMessage() != null
                    ? e.getMessage() : "配置内容不是有效的 S3 JSON";
            new MaterialAlertDialogBuilder(this).setTitle("配置导入失败")
                    .setMessage(message).setPositiveButton("知道了", null).show();
        }
    }

    private static void normalize(S3Config config) {
        config.setBucket(trim(config.getBucket()));
        config.setAccessKey(trim(config.getAccessKey()));
        config.setSecretKey(trim(config.getSecretKey()));
        config.setEndpoint(trim(config.getEndpoint()));
        config.setRegion(trim(config.getRegion()));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private S3Config collect() {
        S3Config config = new S3Config();
        config.setBucket(Ui.value(bucket));
        config.setAccessKey(Ui.value(access));
        config.setSecretKey(Ui.value(secret));
        config.setEndpoint(Ui.value(endpoint));
        config.setRegion(Ui.value(region));
        config.setPathStyle(pathStyle.isChecked());
        return config;
    }

    private void save() {
        if (app.controller().isBusy()) { Toast.makeText(this, "同步处理中，请稍后保存", Toast.LENGTH_LONG).show(); return; }
        try {
            S3Config config = collect();
            S3Client.validate(config);
            boolean changed = model.original == null || !Objects.equals(model.original.getBucket(), config.getBucket())
                    || !Objects.equals(model.original.getEndpoint(), config.getEndpoint())
                    || !Objects.equals(model.original.getRegion(), config.getRegion())
                    || model.original.isPathStyle() != config.isPathStyle();
            // Reset before persisting new credentials so stale records cannot apply to a new target.
            if (changed) app.repository().resetAll();
            app.configStore().write(config);
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            new MaterialAlertDialogBuilder(this).setTitle("配置保存失败")
                    .setMessage(e instanceof IllegalArgumentException ? e.getMessage() : "请检查设备安全存储与可用空间，配置未能保存。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    @Override protected void onPause() {
        if (model != null && bucket != null && access != null && secret != null
                && endpoint != null && region != null && pathStyle != null)
            model.draft = collect();
        super.onPause();
    }
}
