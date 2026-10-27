package com.example.syncgamesave.model;

import java.io.File;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileDigest {
    private File file;
    private long size;
    private String sha256;
    private String md5;
}
