package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FileState {
    private String relativePath;
    private long localModified;
    private long localSize;
    private long sourceModified;
    private String remoteHash;
    private String contentHash;
    private String direction;
    private boolean failed;
    private String error;
}
