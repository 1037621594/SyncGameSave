package com.example.syncgamesave.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RemoteManifest {
    private int version = 1;
    private Map<String, RemoteManifestEntry> files = new LinkedHashMap<>();
}
