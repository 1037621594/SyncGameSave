package com.example.syncgamesave.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.lifecycle.ViewModelProvider;
import com.example.syncgamesave.R;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.storage.PathRules;
import com.example.syncgamesave.sync.RemoteFileDeletion;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Objects;

public class S3BrowserActivity extends AppCompatActivity {
    public static final String EXTRA_SELECT_MODE = "select_mode";
    public static final String EXTRA_PREFIX = "prefix";
    public static final String RESULT_PREFIX = "selected_prefix";
    private static final int MENU_REFRESH = 1;
    private static final int MENU_UP = 2;
    private static final int MENU_JUMP = 3;

    private S3BrowserModel model;
    private S3FileAdapter adapter;
    private ListView fileList;
    private HorizontalScrollView breadcrumbScroll;
    private LinearLayout breadcrumbs;
    private TextView status;
    private View progress;
    private View emptyState;
    private TextView emptyMessage;
    private Button choose;
    private Button more;
    private boolean selectMode;
    private String renderedPrefix;
    private AlertDialog fileDialog;
    private BottomSheetDialog actionSheet;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        selectMode = getIntent().getBooleanExtra(EXTRA_SELECT_MODE, false);
        model = new ViewModelProvider(this).get(S3BrowserModel.class);
        setContentView(R.layout.activity_s3_browser);
        MaterialToolbar toolbar = findViewById(R.id.s3Toolbar);
        toolbar.setTitle(selectMode ? "选择 S3 文件夹" : "S3 文件");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(view -> finish());
        breadcrumbScroll = findViewById(R.id.s3BreadcrumbScroll);
        breadcrumbs = findViewById(R.id.s3Breadcrumbs);
        status = findViewById(R.id.s3Status);
        progress = findViewById(R.id.s3Loading);
        emptyState = findViewById(R.id.s3EmptyState);
        emptyMessage = findViewById(R.id.s3EmptyMessage);
        more = findViewById(R.id.s3More);
        more.setOnClickListener(view -> model.loadMore());
        choose = findViewById(R.id.s3Choose);
        findViewById(R.id.s3SelectionBar).setVisibility(selectMode ? View.VISIBLE : View.GONE);
        choose.setOnClickListener(view -> selectCurrent());
        fileList = findViewById(R.id.s3FileList);
        adapter = new S3FileAdapter(this);
        fileList.setAdapter(adapter);
        fileList.setOnItemClickListener((parent, view, position, id) -> openEntry(adapter.getItem(position)));
        fileList.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(adapter.getItem(position));
            return true;
        });
        model.state().observe(this, this::render);
        model.initialize(state == null ? getIntent().getStringExtra(EXTRA_PREFIX) : state.getString("directory"),
                state == null ? null : state.getString("pending_deletion_key"));
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        Drawable refreshIcon = AppCompatResources.getDrawable(this, R.drawable.ic_s3_refresh);
        if (refreshIcon != null) {
            refreshIcon = refreshIcon.mutate();
            refreshIcon.setTint(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF333333));
        }
        menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, "刷新").setIcon(refreshIcon).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_UP, Menu.NONE, "上一级");
        menu.add(Menu.NONE, MENU_JUMP, Menu.NONE, "跳转到路径");
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        S3BrowserModel.State state = model.state().getValue();
        boolean enabled = state != null && !state.isLoading();
        menu.findItem(MENU_REFRESH).setEnabled(enabled);
        menu.findItem(MENU_UP).setEnabled(enabled && !state.getPrefix().isEmpty());
        menu.findItem(MENU_JUMP).setEnabled(enabled);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        S3BrowserModel.State state = model.state().getValue();
        if (state != null && !state.isLoading()) {
            if (item.getItemId() == MENU_REFRESH) { model.enter(state.getPrefix()); return true; }
            if (item.getItemId() == MENU_UP) { model.up(); return true; }
            if (item.getItemId() == MENU_JUMP) { showJumpDialog(); return true; }
        }
        return super.onOptionsItemSelected(item);
    }

    private void render(S3BrowserModel.State state) {
        boolean sameDirectory = Objects.equals(renderedPrefix, state.getPrefix());
        int position = fileList.getFirstVisiblePosition();
        View first = fileList.getChildAt(0);
        int offset = first == null ? 0 : first.getTop();
        adapter.submit(state, selectMode);
        if (sameDirectory) fileList.setSelectionFromTop(position, offset);
        else fileList.setSelection(0);
        boolean enabled = !state.isLoading();
        fileList.setEnabled(enabled);
        renderBreadcrumbs(state.getPrefix(), enabled, !sameDirectory);
        renderedPrefix = state.getPrefix();
        progress.setVisibility(state.isLoading() ? View.VISIBLE : View.INVISIBLE);
        more.setVisibility(state.getNextMarker().isEmpty() ? View.GONE : View.VISIBLE);
        more.setEnabled(enabled);
        choose.setEnabled(enabled && state.isLoaded() && !state.getPrefix().isEmpty()
                && !RemoteFileDeletion.isMetadata(state.getPrefix()));
        int directories = 0;
        for (String directory : state.getDirectories())
            if (!selectMode || !RemoteFileDeletion.isMetadata(directory)) directories++;
        String summary = directories + " 个文件夹 · " + state.getFiles().size() + " 个文件 · 长按操作";
        if (!state.getMessage().isEmpty()) summary = state.getMessage();
        if (!state.getError().isEmpty()) summary += "\n" + state.getError();
        else if (!state.getPendingDeletionKey().isEmpty() && enabled) summary += "\n有未完成的删除，长按待处理文件重试";
        status.setText(summary);
        status.setTextColor(MaterialColors.getColor(this, state.getError().isEmpty()
                ? com.google.android.material.R.attr.colorOnSurface : com.google.android.material.R.attr.colorError, 0xFF666666));
        status.setAlpha(state.getError().isEmpty() ? 0.65f : 1f);
        emptyState.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
        emptyMessage.setText(state.isLoading() ? "正在加载文件夹…"
                : !state.getError().isEmpty() ? "加载失败，请点击右上角刷新"
                : state.isLoaded() ? "此文件夹为空" : "点击右上角刷新以加载文件");
        invalidateOptionsMenu();
    }

    private void renderBreadcrumbs(String prefix, boolean enabled, boolean revealCurrent) {
        breadcrumbs.removeAllViews();
        addBreadcrumb("根目录", "", enabled, prefix.isEmpty());
        String path = "";
        String[] segments = prefix.split("/", -1);
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            ImageView separator = new ImageView(this);
            separator.setImageResource(R.drawable.ic_s3_chevron);
            separator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            breadcrumbs.addView(separator, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
            path += segment + "/";
            addBreadcrumb(segment.isEmpty() ? "（空名称）" : segment, path, enabled, path.equals(prefix));
        }
        if (revealCurrent) breadcrumbScroll.post(() -> breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void addBreadcrumb(String name, String path, boolean enabled, boolean current) {
        TextView crumb = new TextView(this);
        crumb.setText(name);
        crumb.setTextSize(14);
        crumb.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
        crumb.setGravity(Gravity.CENTER_VERTICAL);
        crumb.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        crumb.setMaxWidth(Ui.dp(this, 200));
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        crumb.setTextColor(MaterialColors.getColor(this, current
                ? com.google.android.material.R.attr.colorOnSurface : com.google.android.material.R.attr.colorPrimary, 0xFF6750A4));
        applyRipple(crumb);
        crumb.setFocusable(!current);
        crumb.setEnabled(enabled && !current);
        crumb.setContentDescription(path.isEmpty() ? "Bucket 根目录" : path);
        crumb.setOnClickListener(view -> model.enter(path));
        breadcrumbs.addView(crumb, new LinearLayout.LayoutParams(-2, -1));
    }

    private void openEntry(S3FileAdapter.Entry entry) {
        if (entry.isDirectory()) model.enter(entry.getKey());
        else if (entry.isPendingDeletion()) showPendingDetails(entry.getKey());
        else showFile(entry.getFile());
    }

    private void showActions(S3FileAdapter.Entry entry) {
        S3BrowserModel.State state = model.state().getValue();
        if (state == null || state.isLoading()) return;
        if (actionSheet != null) actionSheet.dismiss();
        actionSheet = new BottomSheetDialog(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 24));
        TextView title = new TextView(this);
        title.setText(entry.getName());
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF333333));
        title.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        body.addView(title);
        TextView path = new TextView(this);
        path.setText(entry.getKey());
        path.setTextSize(12);
        path.setTextColor(title.getTextColors());
        path.setAlpha(0.6f);
        path.setMaxLines(2);
        path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        path.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12));
        body.addView(path);
        if (entry.isDirectory()) {
            addAction(body, "打开文件夹", R.drawable.ic_s3_folder, false, () -> model.enter(entry.getKey()));
            if (selectMode && !RemoteFileDeletion.isMetadata(entry.getKey()))
                addAction(body, "选择此文件夹", R.drawable.ic_s3_folder, false, () -> selectDirectory(entry.getKey()));
        } else {
            addAction(body, "文件详情", R.drawable.ic_s3_info, false, () -> openEntry(entry));
            boolean canDelete = state.getPendingDeletionKey().isEmpty() || state.getPendingDeletionKey().equals(entry.getKey());
            if (!selectMode && !RemoteFileDeletion.isMetadata(entry.getKey()) && canDelete)
                addAction(body, entry.isPendingDeletion() ? "重试删除并清理本地文件" : "删除文件", R.drawable.ic_s3_delete,
                        true, () -> confirmDelete(entry.getFile()));
        }
        actionSheet.setContentView(body);
        actionSheet.show();
    }

    private void addAction(LinearLayout parent, String title, int iconResource, boolean destructive, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        applyRipple(row);
        int color = MaterialColors.getColor(this, destructive ? com.google.android.material.R.attr.colorError
                : com.google.android.material.R.attr.colorOnSurface, 0xFF333333);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(color);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15);
        label.setTextColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(Ui.dp(this, 20));
        row.addView(label, params);
        row.setContentDescription(title);
        row.setFocusable(true);
        row.setOnClickListener(view -> {
            actionSheet.dismiss();
            action.run();
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
    }

    private void showFile(RemoteFile file) {
        fileDialog = new MaterialAlertDialogBuilder(this).setTitle("文件详情")
                .setMessage("路径：" + file.getKey() + "\n大小：" + S3FileAdapter.formatSize(file.getSize()) + "（" + file.getSize() + " 字节）"
                        + "\n修改时间：" + Ui.time(file.getUploadedAt())
                        + "\nETag：" + (file.getHash() == null ? "未知" : file.getHash()))
                .setPositiveButton("关闭", null).show();
    }

    private void showPendingDetails(String key) {
        fileDialog = new MaterialAlertDialogBuilder(this).setTitle("删除尚未完成")
                .setMessage("路径：" + key + "\n\n请长按此项，选择重试删除，继续清理云端及对应本地文件和同步记录。")
                .setPositiveButton("关闭", null).show();
    }

    private void confirmDelete(RemoteFile file) {
        fileDialog = new MaterialAlertDialogBuilder(this).setTitle("删除云端及本地映射文件？")
                .setMessage(file.getKey() + "\n\n将同时删除此云端文件，以及当前设备所有匹配映射中的对应本地文件（按目录、文件格式和子目录范围匹配）。映射配置和目录保留。删除后无法通过此应用恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> model.delete(file)).show();
    }

    private void showJumpDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
        TextInputEditText path = Ui.field(body, "目录路径，留空为根目录", model.state().getValue().getPrefix(), false);
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fileDialog = new MaterialAlertDialogBuilder(this).setTitle("跳转到路径").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("跳转", null).create();
        fileDialog.setOnShowListener(dialog -> fileDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String input = Ui.value(path);
                if (!input.isEmpty()) PathRules.prefix(input);
                fileDialog.dismiss();
                model.refresh(input);
            } catch (IllegalArgumentException e) { path.setError(e.getMessage()); }
        }));
        fileDialog.show();
    }

    private void selectCurrent() {
        S3BrowserModel.State state = model.state().getValue();
        if (state != null && !state.isLoading() && state.isLoaded()) selectDirectory(state.getPrefix());
    }

    private void selectDirectory(String directory) {
        try {
            if (RemoteFileDeletion.isMetadata(directory))
                throw new IllegalArgumentException("同步元数据目录不能用作路径映射");
            if (!PathRules.prefix(directory).equals(directory))
                throw new IllegalArgumentException("此 S3 目录名称不符合路径映射规则，请选择其他目录");
            setResult(RESULT_OK, new Intent().putExtra(RESULT_PREFIX, directory));
            finish();
        } catch (IllegalArgumentException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void applyRipple(View view) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        if (value.resourceId != 0) view.setBackgroundResource(value.resourceId);
    }

    @Override public void onBackPressed() {
        S3BrowserModel.State state = model.state().getValue();
        if (state != null && !state.isLoading() && !state.getPrefix().isEmpty()) model.up();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        S3BrowserModel.State current = model.state().getValue();
        if (current != null) {
            state.putString("directory", current.getPrefix());
            state.putString("pending_deletion_key", current.getPendingDeletionKey());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (actionSheet != null) actionSheet.dismiss();
        if (fileDialog != null) fileDialog.dismiss();
        super.onDestroy();
    }
}
