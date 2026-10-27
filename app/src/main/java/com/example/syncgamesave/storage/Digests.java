package com.example.syncgamesave.storage;

import android.util.Base64;
import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.sync.CancellationToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public final class Digests {
    private static final int BLOCK_SIZE = 4 * 1024 * 1024;
    private Digests() { }

    public static FileDigest inspect(File file, CancellationToken token) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        MessageDigest block = MessageDigest.getInstance("SHA-1");
        List<byte[]> blocks = new ArrayList<>();
        CRC32 crc32 = new CRC32();
        long size = 0;
        int inBlock = 0;
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream stream = new FileInputStream(file)) {
            int count;
            while ((count = stream.read(buffer, 0, Math.min(buffer.length, BLOCK_SIZE - inBlock))) != -1) {
                token.check();
                sha256.update(buffer, 0, count);
                block.update(buffer, 0, count);
                crc32.update(buffer, 0, count);
                size += count;
                inBlock += count;
                if (inBlock == BLOCK_SIZE) {
                    blocks.add(block.digest());
                    inBlock = 0;
                }
            }
        }
        if (inBlock > 0 || blocks.isEmpty()) blocks.add(block.digest());
        byte[] result = new byte[21];
        byte[] digest;
        if (size <= BLOCK_SIZE) {
            result[0] = 0x16;
            digest = blocks.get(0);
        } else {
            result[0] = (byte) 0x96;
            MessageDigest combined = MessageDigest.getInstance("SHA-1");
            for (byte[] part : blocks) combined.update(part);
            digest = combined.digest();
        }
        System.arraycopy(digest, 0, result, 1, digest.length);
        return new FileDigest(file, size, hex(sha256.digest()),
                Base64.encodeToString(result, Base64.URL_SAFE | Base64.NO_WRAP), crc32.getValue());
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
