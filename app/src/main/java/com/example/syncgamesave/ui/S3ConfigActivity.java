package com.example.syncgamesave.ui;

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
    private CheckBox pathStyle;

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
        Ui.button(body, "保存 S3 配置", this::save);
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
