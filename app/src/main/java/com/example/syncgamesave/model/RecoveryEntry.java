package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RecoveryEntry {
    private String targetUri;
    private String relativePath;
    private String backupName;
    private String backupSha256;
    private boolean newFile;
}
