package com.example.syncgamesave.model;

import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PathMapping {
    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String treeUri = "";
    private String localLabel = "";
    private String remotePrefix = "";
    private String formats = "*";
    private boolean recursive = true;
    private long lastUploadTime;
    private String lastResult = "尚未同步";
    private String lastDirection = "upload";
    private boolean interrupted;
}
