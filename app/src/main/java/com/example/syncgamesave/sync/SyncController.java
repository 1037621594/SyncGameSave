package com.example.syncgamesave.sync;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.syncgamesave.data.SecureConfigStore;
import com.example.syncgamesave.data.SyncRepository;
import com.example.syncgamesave.model.ConflictRequest;
import com.example.syncgamesave.model.PathMapping;
import com.example.syncgamesave.model.SyncResult;
import com.example.syncgamesave.model.TaskStatus;
import com.example.syncgamesave.storage.S3Client;
import com.example.syncgamesave.storage.TreeFiles;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-scoped coordinator: keeps tasks and confirmation decisions across Activity recreation. */
public class SyncController {
    private final SyncRepository repository;
    private final SecureConfigStore configStore;
    private final TreeFiles files;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Long> changes = new MutableLiveData<>(0L);
    private final MutableLiveData<ConflictRequest> conflicts = new MutableLiveData<>();
    private final Map<String, TaskStatus> statuses = new HashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean(true);
    private volatile CancellationToken activeToken;
    private volatile String activeId;
    private volatile String startupError;
    private volatile ConflictRequest pending;
    private long revision;

    public SyncController(SyncRepository repository, SecureConfigStore configStore, TreeFiles files) {
        this.repository = repository;
        this.configStore = configStore;
        this.files = files;
        worker.execute(() -> {
            try { files.recoverPending(); files.clearStaleTransfers(); repository.recoverInterrupted(); }
            catch (Exception e) { startupError = "上次写入恢复失败：" + SyncEngine.message(e) + "。恢复完成前不能同步，可重新授权目录后重试。"; }
            finally { main.post(() -> { busy.set(false); changed(); }); }
        });
    }

    public LiveData<Long> changes() { return changes; }
    public LiveData<ConflictRequest> conflicts() { return conflicts; }
    public TaskStatus status(String id) { return statuses.get(id); }
    public boolean isBusy() { return busy.get(); }
    public String startupError() { return startupError; }

    public void clearHistory() { if (!busy.get()) { statuses.clear(); changed(); } }
    public void removeHistory(String id) { statuses.remove(id); changed(); }

    private void changed() { changes.setValue(++revision); }

    public boolean start(PathMapping mapping, boolean download) {
        if (!busy.compareAndSet(false, true)) return false;
        CancellationToken token = new CancellationToken();
        activeToken = token;
        activeId = mapping.getId();
        TaskStatus initial = new TaskStatus();
        initial.setRunning(true);
        initial.setMessage("准备同步");
        statuses.put(mapping.getId(), initial);
        changed();
        worker.execute(() -> {
            SyncResult outcome = new SyncResult();
            outcome.setDirection(download ? "download" : "upload");
            try {
                files.recoverPending();
                startupError = null;
                mapping.setInterrupted(true);
                mapping.setLastDirection(download ? "download" : "upload");
                repository.saveMapping(mapping);
                try (S3Client client = new S3Client(configStore.read(), token)) {
                    SyncEngine engine = new SyncEngine(repository, files, client, token,
                            (message, done, total) -> publish(mapping.getId(), message, done, total), this::confirm);
                    outcome = download ? engine.download(mapping) : engine.upload(mapping);
                }
            } catch (Exception e) {
                if (token.isCancelled()) outcome.setCancelled(true);
                else { outcome.setFailed(outcome.getFailed() + 1); outcome.addMessage(SyncEngine.message(e)); }
            } finally {
                if (token.isCancelled()) outcome.setCancelled(true);
                if (files.hasPendingRecovery()) startupError = "存在未完成的文件写入，原件备份已保留。请确认原目录授权后重试恢复，恢复完成前不能继续传输。";
                mapping.setInterrupted(false);
                String text = (download ? "下载：" : "上传：") + outcome.summary();
                if (!outcome.getMessages().isEmpty()) text += "\n" + outcome.getMessages().get(0);
                mapping.setLastResult(text);
                try { repository.saveMapping(mapping); }
                catch (Exception e) { outcome.setFailed(outcome.getFailed() + 1); outcome.addMessage("同步结果保存失败，下次将重新核对文件"); }
                SyncResult completed = outcome;
                main.post(() -> {
                    TaskStatus state = new TaskStatus();
                    state.setMessage((download ? "下载：" : "上传：") + completed.summary());
                    state.setResult(completed);
                    statuses.put(mapping.getId(), state);
                    activeToken = null;
                    activeId = null;
                    busy.set(false);
                    changed();
                });
            }
        });
        return true;
    }

    private void publish(String id, String message, int done, int total) {
        main.post(() -> {
            TaskStatus state = new TaskStatus();
            state.setRunning(true);
            state.setMessage(message);
            state.setDone(done);
            state.setTotal(total);
            statuses.put(id, state);
            changed();
        });
    }

    private boolean confirm(String path, long localTime, long remoteTime, String reason, CancellationToken token) throws Exception {
        token.check();
        ConflictRequest request = new ConflictRequest();
        request.setPath(path);
        request.setLocalTime(localTime);
        request.setRemoteTime(remoteTime);
        request.setReason(reason);
        pending = request;
        main.post(() -> conflicts.setValue(request));
        try {
            while (!request.getAnswer().await(250, TimeUnit.MILLISECONDS)) token.check();
            token.check();
            return request.isOverwrite();
        } finally {
            if (pending == request) pending = null;
            main.post(() -> { if (conflicts.getValue() == request) conflicts.setValue(null); });
        }
    }

    public void answer(String id, boolean overwrite) {
        ConflictRequest request = pending;
        if (request != null && request.getId().equals(id)) {
            request.setOverwrite(overwrite);
            request.getAnswer().countDown();
        }
    }

    public void cancel(String id) {
        CancellationToken token = activeToken;
        if (token != null && id.equals(activeId)) token.cancel();
    }

    public void retryRecovery() {
        if (!busy.compareAndSet(false, true)) return;
        changed();
        worker.execute(() -> {
            try { files.recoverPending(); repository.recoverInterrupted(); startupError = null; }
            catch (Exception e) { startupError = "恢复未完成：" + SyncEngine.message(e); }
            finally { main.post(() -> { busy.set(false); changed(); }); }
        });
    }
}



