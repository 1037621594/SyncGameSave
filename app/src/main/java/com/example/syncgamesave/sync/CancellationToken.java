package com.example.syncgamesave.sync;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;

public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Call call;

    public void cancel() {
        cancelled.set(true);
        Call current = call;
        if (current != null) current.cancel();
    }

    public boolean isCancelled() { return cancelled.get(); }

    public void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("任务已取消");
    }

    public void attach(Call current) throws InterruptedIOException {
        check();
        call = current;
        if (cancelled.get()) current.cancel();
        check();
    }

    public void detach(Call current) { if (call == current) call = null; }
}
