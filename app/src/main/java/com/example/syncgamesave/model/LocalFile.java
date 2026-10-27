package com.example.syncgamesave.model;

import android.net.Uri;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LocalFile {
    private Uri uri;
    private String relativePath;
    private long modified;
    private long size;
}
