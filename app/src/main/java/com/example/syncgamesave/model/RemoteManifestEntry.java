package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RemoteManifestEntry {
    private String etag;
    private long size;
    private long sourceModified;
    private String sha256;
}
