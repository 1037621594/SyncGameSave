package com.example.syncgamesave.sync;

import com.example.syncgamesave.data.SyncRepository;
import com.example.syncgamesave.model.LocalFile;
import com.example.syncgamesave.model.PathMapping;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.model.RemoteManifest;
import com.example.syncgamesave.storage.Digests;
import com.example.syncgamesave.storage.PathRules;
import com.example.syncgamesave.storage.S3Client;
import com.example.syncgamesave.storage.TreeFiles;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

public final class RemoteFileDeletion {
    private RemoteFileDeletion() { }

    @Data
    @AllArgsConstructor
    private static class LocalTarget {
        private PathMapping mapping;
        private String relative;
        private LocalFile file;
    }

    public static boolean isMetadata(String key) {
        return key.startsWith("._syncgamesave/") || key.contains("/._syncgamesave/");
    }

    public static void delete(S3Client client, RemoteFile file, TreeFiles files,
                              SyncRepository repository, CancellationToken token) throws Exception {
        String key = file.getKey();
        PathRules.validateRelative(key);
        if (isMetadata(key)) throw new IllegalArgumentException("同步清单由应用维护，不能在文件浏览页删除");
        if (files.hasPendingRecovery()) throw new IOException("存在未完成的本地写入，请先恢复后再删除文件");
        List<LocalTarget> localTargets = new ArrayList<>();
        for (PathMapping mapping : repository.mappings()) {
            token.check();
            if (!key.startsWith(mapping.getRemotePrefix())) continue;
            String relative = key.substring(mapping.getRemotePrefix().length());
            if (!PathRules.matches(mapping, relative)) continue;
            try {
                LocalFile local = files.prepareDeletion(mapping, relative);
                localTargets.add(new LocalTarget(mapping, relative, local));
            } catch (Exception e) {
                throw new IOException("映射“" + mapping.getName() + "”的本地文件检查失败，尚未删除云端文件：" + SyncEngine.message(e), e);
            }
        }
        Gson gson = new Gson();
        Map<String, RemoteManifest> updates = new LinkedHashMap<>();
        // Check every ancestor, including mappings belonging to another device.
        String directory = "";
        int slash = -1;
        while (true) {
            String manifestKey = directory + SyncEngine.MANIFEST_PATH;
            for (RemoteFile candidate : client.list(manifestKey)) {
                if (!manifestKey.equals(candidate.getKey())) continue;
                File temporary = files.newDownload();
                try {
                    client.download(candidate, temporary);
                    RemoteManifest manifest;
                    try (InputStreamReader input = new InputStreamReader(new FileInputStream(temporary), StandardCharsets.UTF_8)) {
                        manifest = gson.fromJson(input, RemoteManifest.class);
                    }
                    if (manifest == null || manifest.getVersion() != 1 || manifest.getFiles() == null)
                        throw new IOException("远端同步清单格式不受支持，文件尚未删除");
                    String relative = key.substring(directory.length());
                    if (manifest.getFiles().containsKey(relative)) {
                        manifest.getFiles().remove(relative);
                        updates.put(manifestKey, manifest);
                    }
                } finally { temporary.delete(); }
                break;
            }
            slash = key.indexOf('/', slash + 1);
            if (slash < 0) break;
            directory = key.substring(0, slash + 1);
        }
        token.check();
        client.delete(key);
        List<String> failures = new ArrayList<>();
        for (LocalTarget target : localTargets) {
            try { files.delete(target.getMapping(), target.getRelative(), target.getFile()); }
            catch (Exception e) { failures.add("本地映射“" + target.getMapping().getName() + "”：" + SyncEngine.message(e)); }
        }
        boolean localFilesDeleted = failures.isEmpty();
        for (Map.Entry<String, RemoteManifest> update : updates.entrySet()) {
            try {
                File temporary = files.newDownload();
                try {
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        output.write(gson.toJson(update.getValue()).getBytes(StandardCharsets.UTF_8));
                        output.getFD().sync();
                    }
                    client.upload(update.getKey(), System.currentTimeMillis(), Digests.inspect(temporary, token));
                } finally { temporary.delete(); }
            } catch (Exception e) {
                failures.add("同步清单“" + update.getKey() + "”：" + SyncEngine.message(e));
            }
        }
        try { repository.forgetRemoteFile(key, localFilesDeleted); }
        catch (Exception e) { failures.add("本地同步记录：" + SyncEngine.message(e)); }
        if (!failures.isEmpty())
            throw new IOException("云端文件已删除，但本地文件或同步记录未全部清理，请点击重试删除。" + failures.get(0));
    }
}
