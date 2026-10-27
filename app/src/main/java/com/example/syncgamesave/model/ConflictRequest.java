package com.example.syncgamesave.model;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ConflictRequest {
    private final String id = UUID.randomUUID().toString();
    private String path;
    private long localTime;
    private long remoteTime;
    private String reason;
    private final CountDownLatch answer = new CountDownLatch(1);
    private volatile boolean overwrite;
}
