package com.example.syncgamesave.ui;

import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.example.syncgamesave.SyncApplication;
import com.example.syncgamesave.model.QiniuConfig;
import com.example.syncgamesave.storage.QiniuClient;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Objects;

public class QiniuConfigActivity extends AppCompatActivity {
    @lombok.Getter
    @lombok.Setter
    public static class DraftModel extends ViewModel {
        QiniuConfig draft;
        QiniuConfig original;
    }

    private DraftModel model;
    private SyncApplication app;
    private TextInputEditText bucket;
    private TextInputEditText access;
    private TextInputEditText secret;
    private TextInputEditText upload;
    private TextInputEditText resource;
    private TextInputEditText list;
    private TextInputEditText domain;
    private CheckBox privateBucket;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        app = (SyncApplication) getApplication();
        model = new ViewModelProvider(this).get(DraftModel.class);
        if (model.draft == null) {
            try { model.original = app.configStore().read(); model.draft = model.original; }
            catch (Exception e) {
                model.draft = new QiniuConfig();
                new MaterialAlertDialogBuilder(this).setTitle("无法读取原凭证")
                        .setMessage("安全存储中的凭证无法解密。你可以重新填写并保存；保存会替换原配置并重置同步基线。")
                        .setPositiveButton("重新填写", null).show();
            }
        }
        QiniuConfig config = model.draft;
        LinearLayout body = Ui.screen(this, "七牛配置");
        Ui.text(body, "配置本机直连七牛使用的凭证。所有地址使用 HTTPS；服务地址须与空间实际区域匹配。", 14);
        bucket = Ui.field(body, "空间名称（Bucket）", config.getBucket(), false);
        access = Ui.field(body, "AccessKey", config.getAccessKey(), true);
        secret = Ui.field(body, "SecretKey", config.getSecretKey(), true);
        upload = Ui.field(body, "上传服务地址", config.getUploadEndpoint(), false);
        resource = Ui.field(body, "资源管理地址（stat）", config.getResourceEndpoint(), false);
        list = Ui.field(body, "资源列举地址（list）", config.getListEndpoint(), false);
        domain = Ui.field(body, "下载域名，例如 https://save.example.com", config.getDownloadDomain(), false);
        privateBucket = new CheckBox(this);
        privateBucket.setText("私有空间（下载时生成限时访问签名）");
        privateBucket.setChecked(config.isPrivateBucket());
        body.addView(privateBucket);
        Ui.text(body, "更换空间或服务地址会清除文件同步基线，下一次上传按首次同步处理。更换密钥或下载域名不重置基线。凭证加密保存，不随系统备份导出。", 14);
        Ui.button(body, "保存七牛配置", this::save);
    }

    private QiniuConfig collect() {
        QiniuConfig config = new QiniuConfig();
        config.setBucket(Ui.value(bucket));
        config.setAccessKey(Ui.value(access));
        config.setSecretKey(Ui.value(secret));
        config.setUploadEndpoint(Ui.value(upload));
        config.setResourceEndpoint(Ui.value(resource));
        config.setListEndpoint(Ui.value(list));
        config.setDownloadDomain(Ui.value(domain));
        config.setPrivateBucket(privateBucket.isChecked());
        return config;
    }

    private void save() {
        if (app.controller().isBusy()) { Toast.makeText(this, "同步处理中，请稍后保存", Toast.LENGTH_LONG).show(); return; }
        try {
            QiniuConfig config = collect();
            QiniuClient.validate(config);
            boolean changed = model.original == null || !Objects.equals(model.original.getBucket(), config.getBucket())
                    || !Objects.equals(model.original.getUploadEndpoint(), config.getUploadEndpoint())
                    || !Objects.equals(model.original.getResourceEndpoint(), config.getResourceEndpoint())
                    || !Objects.equals(model.original.getListEndpoint(), config.getListEndpoint());
            // Reset first: if encryption/storage fails, conservative re-upload is safer than stale records.
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
        if (bucket != null) model.draft = collect();
        super.onPause();
    }
}

