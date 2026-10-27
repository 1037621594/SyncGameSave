package com.example.syncgamesave.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SyncResult {
    private String direction;
    private int success;
    private int unchanged;
    private int skipped;
    private int failed;
    private int deferred;
    private boolean cancelled;
    private final List<String> messages = new ArrayList<>();

    public String summary() {
        return (cancelled ? "已取消；" : "") + "成功 " + success + "，未变化 " + unchanged
                + "，跳过 " + skipped + "，失败 " + failed + "，待下次 " + deferred;
    }

    public void addMessage(String message) {
        if (messages.size() < 100) messages.add(message);
    }
}
