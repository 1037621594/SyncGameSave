package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RemoteFile {
    private String key;
    /** Opaque remote ETag, including quotes when returned by S3. */
    private String hash;
    private long size;
    private long sourceModified;
    private String contentHash;
    private long uploadedAt;
}
