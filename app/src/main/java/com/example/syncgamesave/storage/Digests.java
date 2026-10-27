package com.example.syncgamesave.storage;

import android.util.Base64;
import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.sync.CancellationToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

public final class Digests {
    private Digests() { }

    public static FileDigest inspect(File file, CancellationToken token) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream stream = new FileInputStream(file)) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                token.check();
                sha256.update(buffer, 0, count);
                md5.update(buffer, 0, count);
                size += count;
            }
        }
        return new FileDigest(file, size, hex(sha256.digest()),
                Base64.encodeToString(md5.digest(), Base64.NO_WRAP));
    }

    public static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }

    public static File temporary(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建临时目录");
        return File.createTempFile("sync-", ".tmp", directory);
    }
}
