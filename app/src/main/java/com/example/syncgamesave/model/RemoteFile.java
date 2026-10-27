package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RemoteFile {
    private String key;
    private String hash;
    private long size;
    private long sourceModified;
    private String contentHash;
    private long uploadedAt;
}
