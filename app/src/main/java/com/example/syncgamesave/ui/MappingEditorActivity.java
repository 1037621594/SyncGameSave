package com.example.syncgamesave.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.example.syncgamesave.SyncApplication;
import com.example.syncgamesave.model.PathMapping;
import com.example.syncgamesave.storage.PathRules;
import com.example.syncgamesave.storage.TreeFiles;
import com.example.syncgamesave.sync.SyncEngine;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import java.util.Objects;

public class MappingEditorActivity extends AppCompatActivity {
    public static final String MAPPING_ID = "mapping_id";
    private PathMapping original;
    private PathMapping draft;
    private SyncApplication app;
    private TextInputEditText name;
    private TextInputEditText prefix;
    private TextInputEditText formats;
    private TextView directory;
    private CheckBox recursive;
    private final ActivityResultLauncher<Intent> directoryPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                int permissions = result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((permissions & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 || (permissions & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    toast("请选择可读写的目录"); return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(uri, permissions);
                    String label = DocumentsContract.getTreeDocumentId(uri);
                    Uri root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                    try (Cursor cursor = getContentResolver().query(root,
                            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) label = cursor.getString(0) + "（" + label + "）";
                    }
                    draft.setTreeUri(uri.toString());
                    draft.setLocalLabel(label);
                    directory.setText("本地目录：" + label);
                } catch (Exception e) { toast("目录授权失败，请重新选择可持久访问的目录"); }
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (SyncApplication) getApplication();
        String id = getIntent().getStringExtra(MAPPING_ID);
        if (id != null) {
            for (PathMapping mapping : app.repository().mappings()) if (mapping.getId().equals(id)) original = mapping;
            if (original == null) { toast("映射不存在"); finish(); return; }
        }
        draft = state == null ? (original == null ? new PathMapping() : new Gson().fromJson(new Gson().toJson(original), PathMapping.class))
                : new Gson().fromJson(state.getString("draft"), PathMapping.class);
        LinearLayout body = Ui.screen(this, original == null ? "添加路径" : "编辑路径");
        name = Ui.field(body, "映射名称", draft.getName(), false);
        directory = Ui.text(body, "本地目录：" + (draft.getTreeUri().isEmpty() ? "尚未选择" : draft.getLocalLabel()), 15);
        Ui.button(body, "选择本地目录／重新授权", this::chooseDirectory);
        prefix = Ui.field(body, "远端目录，例如 saves/device-a/", draft.getRemotePrefix(), false);
        formats = Ui.field(body, "文件扩展名，例如 sav,srm,state；* 表示全部", draft.getFormats(), false);
        recursive = new CheckBox(this);
        recursive.setText("包含所有子目录，并保留相对目录结构");
        recursive.setChecked(draft.isRecursive());
        body.addView(recursive);
        Ui.text(body, "保存后会立即上传未同步文件，并覆盖远端同名对象。不会删除本地或云端文件。", 14);
        Ui.button(body, "保存并上传", this::save);
    }

    private void chooseDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= 26 && !draft.getTreeUri().isEmpty())
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(draft.getTreeUri()));
        try { directoryPicker.launch(intent); }
        catch (Exception e) { toast("设备没有可用的系统目录选择器"); }
    }

    private void collect() {
        draft.setName(Ui.value(name));
        draft.setRemotePrefix(Ui.value(prefix));
        draft.setFormats(Ui.value(formats));
        draft.setRecursive(recursive.isChecked());
    }

    private void save() {
        if (app.controller().isBusy()) { toast("有任务正在处理，请完成后再保存"); return; }
        try {
            collect();
            if (draft.getName().isEmpty()) throw new IllegalArgumentException("请填写映射名称");
            if (draft.getTreeUri().isEmpty()) throw new IllegalArgumentException("请选择本地目录");
            draft.setRemotePrefix(PathRules.prefix(draft.getRemotePrefix()));
            draft.setFormats(PathRules.formats(draft.getFormats()));
            new TreeFiles(this).root(draft);
            for (PathMapping other : app.repository().mappings()) {
                if (other.getId().equals(draft.getId())) continue;
                if (PathRules.conflicts(draft, other)) throw new IllegalArgumentException("远端目录与“" + other.getName() + "”的范围重叠，可能覆盖同一个对象，请调整目录或格式");
            }
            boolean reset = original == null || !Objects.equals(original.getTreeUri(), draft.getTreeUri())
                    || !Objects.equals(original.getRemotePrefix(), draft.getRemotePrefix())
                    || !Objects.equals(original.getFormats(), draft.getFormats()) || original.isRecursive() != draft.isRecursive();
            app.repository().saveAndReset(draft, reset);
            setResult(RESULT_OK, new Intent().putExtra(MAPPING_ID, draft.getId()));
            finish();
        } catch (Exception e) {
            new MaterialAlertDialogBuilder(this).setTitle("无法保存路径").setMessage(SyncEngine.message(e))
                    .setPositiveButton("知道了", null).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (draft != null && name != null) { collect(); state.putString("draft", new Gson().toJson(draft)); }
        super.onSaveInstanceState(state);
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}

