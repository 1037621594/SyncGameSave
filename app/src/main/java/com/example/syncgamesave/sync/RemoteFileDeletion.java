package com.example.syncgamesave.sync;

import com.example.syncgamesave.data.SyncRepository;
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
import java.util.LinkedHashMap;
import java.util.Map;

public final class RemoteFileDeletion {
    private RemoteFileDeletion() { }

    public static boolean isMetadata(String key) {
        return key.startsWith("._syncgamesave/") || key.contains("/._syncgamesave/");
    }

    public static void delete(S3Client client, RemoteFile file, TreeFiles files,
                              SyncRepository repository, CancellationToken token) throws Exception {
        String key = file.getKey();
        PathRules.validateRelative(key);
        if (isMetadata(key)) throw new IllegalArgumentException("同步清单由应用维护，不能在文件浏览页删除");
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
        try {
            repository.forgetRemoteFile(key);
            for (Map.Entry<String, RemoteManifest> update : updates.entrySet()) {
                File temporary = files.newDownload();
                try {
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        output.write(gson.toJson(update.getValue()).getBytes(StandardCharsets.UTF_8));
                        output.getFD().sync();
                    }
                    client.upload(update.getKey(), System.currentTimeMillis(), Digests.inspect(temporary, token));
                } finally { temporary.delete(); }
            }
        } catch (Exception e) {
            throw new IOException("文件已从远端删除，但同步记录更新未完成，请重试删除以完成清理。" + SyncEngine.message(e), e);
        }
    }
}
