package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskStatus {
    private boolean running;
    private String message;
    private int done;
    private int total;
    private SyncResult result;
}
