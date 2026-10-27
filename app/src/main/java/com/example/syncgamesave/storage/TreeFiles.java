package com.example.syncgamesave.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.model.LocalFile;
import com.example.syncgamesave.model.PathMapping;
import com.example.syncgamesave.model.RecoveryEntry;
import com.example.syncgamesave.sync.CancellationToken;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;

public class TreeFiles {
    private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS};
    private final ContentResolver resolver;
    private final File cache;
    private final File recovery;
    private final Gson gson = new Gson();

    public TreeFiles(Context context) {
        resolver = context.getContentResolver();
        cache = new File(context.getCacheDir(), "transfers");
        recovery = new File(context.getFilesDir(), "recovery");
    }

    @Data @AllArgsConstructor
    private static class Node {
        private Uri uri;
        private String name;
        private String mime;
        private long modified;
        private long size;
        private long flags;
    }

    @Data @AllArgsConstructor
    private static class Directory {
        private Uri uri;
        private String prefix;
    }

    private Node node(Uri uri, Cursor cursor) throws IOException {
        String id = cursor.getString(0);
        String name = cursor.getString(1);
        if (id == null || name == null) throw new IOException("目录提供方返回了无效文件");
        return new Node(DocumentsContract.buildDocumentUriUsingTree(uri, id), name, cursor.getString(2),
                cursor.isNull(3) ? 0 : cursor.getLong(3), cursor.isNull(4) ? -1 : cursor.getLong(4), cursor.getLong(5));
    }

    public Uri root(PathMapping mapping) throws IOException {
        try {
            Uri tree = Uri.parse(mapping.getTreeUri());
            Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = resolver.query(root, COLUMNS, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("目录不存在，请重新选择");
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) throw new IOException("所选位置不是目录");
            }
            return root;
        } catch (SecurityException e) { throw new IOException("目录授权失效，请编辑路径并重新授权", e); }
    }

    private List<Node> children(Uri directory) throws IOException {
        List<Node> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory));
        try (Cursor cursor = resolver.query(query, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("读取目录失败");
            while (cursor.moveToNext()) {
                Node item = node(directory, cursor);
                if (!names.add(item.getName())) throw new IOException("目录内有重复文件名，无法安全映射：" + item.getName());
                result.add(item);
            }
            if (cursor.getExtras() != null && cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false))
                throw new IOException("目录尚未加载完整，请稍后重试");
        } catch (SecurityException e) { throw new IOException("没有目录读取权限，请重新授权", e); }
        return result;
    }

    public List<LocalFile> scan(PathMapping mapping, CancellationToken token) throws IOException {
        List<LocalFile> result = new ArrayList<>();
        ArrayDeque<Directory> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new Directory(root(mapping), ""));
        while (!queue.isEmpty()) {
            token.check();
            Directory directory = queue.removeFirst();
            if (!visited.add(directory.getUri().toString())) throw new IOException("目录存在重复引用，停止扫描");
            for (Node item : children(directory.getUri())) {
                token.check();
                String path = directory.getPrefix() + item.getName();
                PathRules.validateRelative(path);
                if (item.getName().contains("/")) throw new IOException("文件名包含目录分隔符");
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(item.getMime())) {
                    if (mapping.isRecursive()) queue.add(new Directory(item.getUri(), path + "/"));
                } else if (PathRules.matches(mapping, path)) {
                    if ((item.getFlags() & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0)
                        throw new IOException("不支持虚拟文件：" + path);
                    result.add(new LocalFile(item.getUri(), path, item.getModified(), item.getSize()));
                }
            }
        }
        return result;
    }

    public LocalFile stat(Uri uri, String relative) throws IOException {
        try (Cursor cursor = resolver.query(uri, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("读取文件信息失败：" + relative);
            if (!cursor.moveToFirst()) return null;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) throw new IOException("目标是目录：" + relative);
            Node value = node(uri, cursor);
            return new LocalFile(uri, relative, value.getModified(), value.getSize());
        } catch (SecurityException e) { throw new IOException("文件访问权限失效：" + relative, e); }
    }

    public LocalFile find(PathMapping mapping, String relative) throws IOException {
        Uri parent = resolveParent(mapping, relative, false);
        if (parent == null) return null;
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        List<Node> entries = children(parent);
        for (Node item : entries) {
            if (item.getName().equals(name)) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(item.getMime())) throw new IOException("同名位置是目录：" + relative);
                return new LocalFile(item.getUri(), relative, item.getModified(), item.getSize());
            }
        }
        for (Node item : entries) if (item.getName().equalsIgnoreCase(name))
            throw new IOException("目录内存在仅大小写不同的名称，为避免错误覆盖请先重命名：" + relative);
        return null;
    }

    private Uri resolveParent(PathMapping mapping, String relative, boolean create) throws IOException {
        PathRules.validateRelative(relative);
        String[] parts = relative.split("/");
        Uri directory = root(mapping);
        for (int i = 0; i < parts.length - 1; i++) {
            Node found = null;
            for (Node item : children(directory)) if (item.getName().equals(parts[i])) found = item;
            if (found == null) {
                if (!create) return null;
                directory = createExact(directory, DocumentsContract.Document.MIME_TYPE_DIR, parts[i]);
            } else {
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(found.getMime())) throw new IOException("子目录与文件重名");
                directory = found.getUri();
            }
        }
        return directory;
    }

    private Uri createExact(Uri parent, String mime, String name) throws IOException {
        List<Node> before = children(parent);
        for (Node node : before) if (node.getName().equalsIgnoreCase(name))
            throw new IOException("目标名称已存在或有大小写冲突，请重试：" + name);
        Uri created = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (created == null) throw new IOException("创建本地文件或目录失败：" + name);
        for (Node node : before) if (node.getUri().equals(created))
            throw new IOException("目录提供方返回了已有对象，停止写入以保护原文件");
        boolean exact = false;
        try (Cursor cursor = resolver.query(created, COLUMNS, null, null, null)) {
            exact = cursor != null && cursor.moveToFirst() && name.equals(cursor.getString(1));
        }
        if (!exact) {
            DocumentsContract.deleteDocument(resolver, created);
            throw new IOException("目录提供方改变了文件名，无法保持路径映射：" + name);
        }
        return created;
    }

    public FileDigest snapshot(LocalFile local, CancellationToken token) throws Exception {
        File file = Digests.temporary(cache);
        try {
            try (InputStream input = resolver.openInputStream(local.getUri()); FileOutputStream output = new FileOutputStream(file)) {
                copy(input, output, token);
                output.getFD().sync();
            }
            FileDigest digest = Digests.inspect(file, token);
            if (local.getSize() >= 0 && digest.getSize() != local.getSize()) throw new IOException("本地读取长度与文件信息不一致，请重试");
            return digest;
        } catch (Exception e) { file.delete(); throw e; }
    }

    public File newDownload() throws IOException { return Digests.temporary(cache); }

    public void clearStaleTransfers() {
        File[] entries = cache.listFiles();
        if (entries == null) return;
        for (File file : entries) if (file.isFile() && file.getName().startsWith("sync-") && file.getName().endsWith(".tmp")) file.delete();
    }

    public static boolean same(LocalFile a, LocalFile b) {
        return a == null ? b == null : b != null && a.getUri().equals(b.getUri())
                && a.getModified() == b.getModified() && a.getSize() == b.getSize();
    }

    public LocalFile replace(PathMapping mapping, String relative, LocalFile expected,
                             String expectedSha256, FileDigest downloaded, CancellationToken token) throws Exception {
        token.check();
        recoverPending();
        if (!same(expected, find(mapping, relative))) throw new IOException("本地文件发生变化，请重试：" + relative);
        if (!recovery.exists() && !recovery.mkdirs()) throw new IOException("无法创建恢复目录");
        RecoveryEntry entry = new RecoveryEntry();
        entry.setRelativePath(relative);
        entry.setNewFile(expected == null);
        File backup = null;
        Uri target;
        if (expected != null) {
            backup = File.createTempFile("original-", ".bak", recovery);
            try {
                try (InputStream input = resolver.openInputStream(expected.getUri()); FileOutputStream output = new FileOutputStream(backup)) {
                    copy(input, output, token);
                    output.getFD().sync();
                }
                FileDigest digest = Digests.inspect(backup, token);
                if (!digest.getSha256().equals(expectedSha256)) throw new IOException("备份期间本地内容发生变化，请重新确认");
                entry.setBackupName(backup.getName());
                entry.setBackupSha256(digest.getSha256());
                if (!same(expected, stat(expected.getUri(), relative))) throw new IOException("备份期间本地文件发生变化，请重试");
            } catch (Exception e) { backup.delete(); throw e; }
            target = expected.getUri();
        } else {
            Uri parent = resolveParent(mapping, relative, true);
            if (find(mapping, relative) != null) throw new IOException("本地已出现同名文件，请重试");
            target = createExact(parent, "application/octet-stream", relative.substring(relative.lastIndexOf('/') + 1));
        }
        entry.setTargetUri(target.toString());
        File journal = new File(recovery, "pending.json");
        boolean journalSaved = false;
        try {
            writeJournal(journal, entry);
            journalSaved = true;
            // Once the journal is durable, finish replacement or restoration without cancellation.
            write(target, downloaded.getFile());
            FileDigest written = snapshot(statRequired(target, relative), new CancellationToken());
            try {
                if (!downloaded.getSha256().equals(written.getSha256())) throw new IOException("本地写入校验失败");
            } finally { written.getFile().delete(); }
            LocalFile completed = statRequired(target, relative);
            if (!journal.delete()) throw new IOException("无法清除恢复日志");
            if (backup != null) backup.delete();
            return completed;
        } catch (Exception e) {
            if (journalSaved) {
                try {
                    restore(entry);
                    if (!journal.delete()) throw new IOException("恢复后无法清除日志，备份已保留");
                    if (backup != null) backup.delete();
                }
                catch (Exception restoreError) { throw new IOException("写入失败且恢复未完成，原件备份已保留；请重启应用重试恢复：" + relative, restoreError); }
            } else {
                if (expected == null) DocumentsContract.deleteDocument(resolver, target);
                if (backup != null) backup.delete();
            }
            throw e;
        }
    }

    private LocalFile statRequired(Uri uri, String relative) throws IOException {
        LocalFile value = stat(uri, relative);
        if (value == null) throw new IOException("写入后的文件不存在");
        return value;
    }

    private void writeJournal(File journal, RecoveryEntry entry) throws IOException {
        android.util.AtomicFile atomic = new android.util.AtomicFile(journal);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(gson.toJson(entry).getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (IOException e) { if (stream != null) atomic.failWrite(stream); throw e; }
    }

    private void write(Uri uri, File source) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = resolver.openOutputStream(uri, "wt")) {
            copy(input, output, new CancellationToken());
        }
    }

    private void restore(RecoveryEntry entry) throws Exception {
        Uri uri = Uri.parse(entry.getTargetUri());
        if (entry.isNewFile()) {
            LocalFile current = stat(uri, entry.getRelativePath());
            if (current != null && !DocumentsContract.deleteDocument(resolver, uri)) throw new IOException("清理未完成文件失败");
            return;
        }
        File backup = new File(recovery, entry.getBackupName());
        FileDigest digest = Digests.inspect(backup, new CancellationToken());
        if (!digest.getSha256().equals(entry.getBackupSha256())) throw new IOException("恢复备份校验失败");
        write(uri, backup);
        FileDigest restored = snapshot(statRequired(uri, entry.getRelativePath()), new CancellationToken());
        try {
            if (!restored.getSha256().equals(entry.getBackupSha256())) throw new IOException("恢复后的文件校验失败");
        } finally { restored.getFile().delete(); }
    }

    public boolean hasPendingRecovery() {
        File journal = new File(recovery, "pending.json");
        return journal.exists() || new File(journal.getPath() + ".bak").exists();
    }

    public void recoverPending() throws Exception {
        File journal = new File(recovery, "pending.json");
        android.util.AtomicFile atomic = new android.util.AtomicFile(journal);
        if (!journal.exists() && !new File(journal.getPath() + ".bak").exists()) return;
        RecoveryEntry entry;
        try (InputStream input = atomic.openRead(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            copy(input, output, new CancellationToken());
            entry = gson.fromJson(output.toString("UTF-8"), RecoveryEntry.class);
        }
        if (entry == null || entry.getTargetUri() == null) throw new IOException("恢复日志无效");
        restore(entry);
        atomic.delete();
        if (journal.exists() || new File(journal.getPath() + ".bak").exists()) throw new IOException("无法清除恢复日志，备份已保留");
        if (entry.getBackupName() != null) new File(recovery, entry.getBackupName()).delete();
    }

    public static void copy(InputStream input, OutputStream output, CancellationToken token) throws IOException {
        if (input == null || output == null) throw new IOException("无法打开文件流");
        byte[] buffer = new byte[64 * 1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            token.check();
            output.write(buffer, 0, length);
        }
        output.flush();
    }
}




