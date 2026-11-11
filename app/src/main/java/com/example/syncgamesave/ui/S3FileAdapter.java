package com.example.syncgamesave.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.example.syncgamesave.R;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.sync.RemoteFileDeletion;
import com.google.android.material.color.MaterialColors;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Recycles compact file rows while keeping file actions in the long-press menu. */
public class S3FileAdapter extends BaseAdapter {
    @Data
    @AllArgsConstructor
    public static class Entry {
        private String key;
        private String name;
        private boolean directory;
        private RemoteFile file;
        private boolean pendingDeletion;
    }

    @AllArgsConstructor
    private static class Holder {
        private final ImageView icon;
        private final TextView name;
        private final TextView subtitle;
        private final View chevron;
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Entry> entries = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private boolean enabled = true;

    public S3FileAdapter(Context context) {
        this.context = context;
        inflater = LayoutInflater.from(context);
    }

    public void submit(S3BrowserModel.State state, boolean selectMode) {
        entries.clear();
        enabled = !state.isLoading();
        String pending = selectMode ? "" : state.getPendingDeletionKey();
        boolean pendingVisible = false;
        for (String directory : state.getDirectories()) {
            if (selectMode && RemoteFileDeletion.isMetadata(directory)) continue;
            String name = directory.substring(state.getPrefix().length());
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            entries.add(new Entry(directory, name.isEmpty() ? "（空名称）" : name, true, null, false));
        }
        for (RemoteFile file : state.getFiles()) {
            boolean waiting = file.getKey().equals(pending);
            pendingVisible |= waiting;
            entries.add(new Entry(file.getKey(), file.getKey().substring(state.getPrefix().length()), false, file, waiting));
        }
        // A failed local deletion must remain reachable after the cloud file disappears or the user navigates away.
        if (!pending.isEmpty() && !pendingVisible) {
            RemoteFile file = new RemoteFile();
            file.setKey(pending);
            entries.add(0, new Entry(pending, pending, false, file, true));
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return entries.size(); }
    @Override public Entry getItem(int position) { return entries.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return enabled; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        Holder holder;
        if (row == null) {
            row = inflater.inflate(R.layout.item_s3_file, parent, false);
            holder = new Holder(row.findViewById(R.id.s3EntryIcon), row.findViewById(R.id.s3EntryName),
                    row.findViewById(R.id.s3EntrySubtitle), row.findViewById(R.id.s3EntryChevron));
            row.setTag(holder);
        } else holder = (Holder) row.getTag();
        Entry entry = getItem(position);
        holder.icon.setImageResource(entry.isDirectory() ? R.drawable.ic_s3_folder : R.drawable.ic_s3_file);
        holder.name.setText(entry.getName());
        holder.chevron.setVisibility(entry.isDirectory() ? View.VISIBLE : View.GONE);
        if (entry.isPendingDeletion()) holder.subtitle.setText("删除尚未完成 · 长按重试");
        else if (entry.isDirectory()) holder.subtitle.setText(RemoteFileDeletion.isMetadata(entry.getKey()) ? "同步元数据文件夹" : "文件夹");
        else {
            RemoteFile file = entry.getFile();
            String modified = file.getUploadedAt() > 0 ? dateFormat.format(new Date(file.getUploadedAt())) : "时间未知";
            holder.subtitle.setText(formatSize(file.getSize()) + "  ·  " + modified);
        }
        holder.subtitle.setTextColor(entry.isPendingDeletion()
                ? MaterialColors.getColor(context, com.google.android.material.R.attr.colorError, 0xFFB3261E)
                : holder.name.getTextColors().getDefaultColor());
        holder.subtitle.setAlpha(entry.isPendingDeletion() ? 1f : 0.58f);
        row.setAlpha(enabled ? 1f : 0.5f);
        row.setContentDescription((entry.isDirectory() ? "文件夹，" : "文件，") + entry.getName()
                + "，" + holder.subtitle.getText() + "，长按打开操作菜单");
        return row;
    }

    public static String formatSize(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        if (value < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GiB", value / (1024.0 * 1024 * 1024));
    }
}
