package com.example.syncgamesave.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class S3DirectoryPage {
    private List<String> directories = new ArrayList<>();
    private List<RemoteFile> files = new ArrayList<>();
    private String nextMarker = "";
}
