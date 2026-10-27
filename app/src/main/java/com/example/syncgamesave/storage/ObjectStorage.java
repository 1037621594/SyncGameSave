package com.example.syncgamesave.storage;

import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.model.RemoteFile;
import java.io.Closeable;
import java.io.File;
import java.util.List;

/** Transport boundary; ETags are opaque version identifiers, not content checksums. */
public interface ObjectStorage extends Closeable {
    List<RemoteFile> list(String prefix) throws Exception;
    RemoteFile stat(String key) throws Exception;
    RemoteFile upload(String key, long sourceModified, FileDigest file) throws Exception;
    FileDigest download(RemoteFile remote, File destination) throws Exception;
}
