package com.example.syncgamesave.ui;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.syncgamesave.SyncApplication;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.storage.PathRules;
import com.example.syncgamesave.storage.TreeFiles;
import com.example.syncgamesave.sync.CancellationToken;
import com.example.syncgamesave.sync.RemoteFileDeletion;
import com.example.syncgamesave.sync.SyncEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.Data;
import lombok.NoArgsConstructor;

public class S3BrowserModel extends AndroidViewModel {
    @Data
    @NoArgsConstructor
    public static class State {
        private String prefix = "";
        private List<String> directories = new ArrayList<>();
        private List<RemoteFile> files = new ArrayList<>();
        private String nextMarker = "";
        private String message = "";
        private String error = "";
        private String pendingDeletionKey = "";
        private boolean loading;
        private boolean loaded;
    }

    private final SyncApplication app;
    private final MutableLiveData<State> state = new MutableLiveData<>(new State());
    private final Set<String> visitedMarkers = new HashSet<>();
    private CancellationToken activeToken;
    private boolean initialized;
    private boolean deleting;
    private boolean cleared;

    public S3BrowserModel(Application application) {
        super(application);
        app = (SyncApplication) application;
    }

    public LiveData<State> state() { return state; }

    public void initialize(String initial, String pendingDeletionKey) {
        if (initialized) return;
        initialized = true;
        String directory;
        try { directory = normalizePrefix(initial); }
        catch (IllegalArgumentException e) { directory = ""; }
        state.getValue().setPrefix(directory);
        state.getValue().setPendingDeletionKey(pendingDeletionKey == null ? "" : pendingDeletionKey);
        load(directory, false);
    }

    public void refresh(String input) {
        try { load(normalizePrefix(input), false); }
        catch (IllegalArgumentException e) { report(SyncEngine.message(e)); }
    }

    public void enter(String directory) { load(directory, false); }

    public void loadMore() {
        State current = state.getValue();
        if (!current.getNextMarker().isEmpty()) load(current.getPrefix(), true);
    }

    public void up() {
        String current = state.getValue().getPrefix();
        if (current.isEmpty()) return;
        int slash = current.lastIndexOf('/', current.length() - 2);
        load(slash < 0 ? "" : current.substring(0, slash + 1), false);
    }

    private void load(String directory, boolean more) {
        State current = state.getValue();
        if (current.isLoading() || cleared) return;
        if (app.controller().isBusy()) { report("有任务正在处理，请完成后刷新 S3 列表"); return; }
        String marker = more ? current.getNextMarker() : "";
        if (!more) {
            current.setPrefix(directory);
            current.getDirectories().clear();
            current.getFiles().clear();
            current.setNextMarker("");
            current.setLoaded(false);
            visitedMarkers.clear();
        }
        current.setLoading(true);
        current.setError("");
        current.setMessage("正在读取 S3 目录……");
        state.setValue(current);
        activeToken = new CancellationToken();
        boolean started = app.controller().runStorageTask(activeToken,
                (client, token) -> client.listDirectory(directory, marker), (page, error) -> {
                    activeToken = null;
                    if (cleared) return;
                    current.setLoading(false);
                    if (error != null) {
                        current.setMessage("");
                        current.setError("读取失败：" + SyncEngine.message(error));
                    } else if (!page.getNextMarker().isEmpty() && visitedMarkers.contains(page.getNextMarker())) {
                        current.setMessage("");
                        current.setError("S3 返回了重复的分页标记，请刷新重试");
                    } else {
                        visitedMarkers.add(marker);
                        Set<String> directories = new TreeSet<>(current.getDirectories());
                        directories.addAll(page.getDirectories());
                        current.setDirectories(new ArrayList<>(directories));
                        Set<String> keys = new HashSet<>();
                        for (RemoteFile file : current.getFiles()) keys.add(file.getKey());
                        for (RemoteFile file : page.getFiles()) {
                            if (!file.getKey().equals(directory) && keys.add(file.getKey())) current.getFiles().add(file);
                        }
                        Collections.sort(current.getFiles(),
                                (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getKey(), b.getKey()));
                        current.setNextMarker(page.getNextMarker());
                        current.setLoaded(true);
                        current.setMessage("");
                        current.setError("");
                    }
                    state.setValue(current);
                });
        if (!started) {
            activeToken = null;
            current.setLoading(false);
            report("有任务正在处理，请完成后刷新 S3 列表");
        }
    }

    public void delete(RemoteFile file) {
        State current = state.getValue();
        if (current.isLoading() || cleared) return;
        if (!current.getPendingDeletionKey().isEmpty() && !current.getPendingDeletionKey().equals(file.getKey())) {
            report("请先重试完成上一个文件的删除清理");
            return;
        }
        current.setLoading(true);
        current.setMessage("正在删除云端及本地映射文件：" + file.getKey());
        current.setError("");
        current.setPendingDeletionKey(file.getKey());
        state.setValue(current);
        deleting = true;
        activeToken = new CancellationToken();
        boolean started = app.controller().runStorageTask(activeToken, (client, token) -> {
            RemoteFileDeletion.delete(client, file, new TreeFiles(app), app.repository(), token);
            return file.getKey();
        }, (key, error) -> {
            activeToken = null;
            deleting = false;
            current.setLoading(false);
            app.controller().clearHistory();
            if (cleared) return;
            if (error == null) {
                current.setPendingDeletionKey("");
                load(current.getPrefix(), false);
            } else {
                current.setMessage("");
                current.setError("删除失败：" + SyncEngine.message(error));
                state.setValue(current);
            }
        });
        if (!started) {
            activeToken = null;
            deleting = false;
            current.setLoading(false);
            report("有任务正在处理，请完成后重试删除");
        }
    }

    private void report(String error) {
        State current = state.getValue();
        current.setError(error);
        current.setMessage("");
        state.setValue(current);
    }

    private static String normalizePrefix(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        return PathRules.prefix(input);
    }

    @Override protected void onCleared() {
        cleared = true;
        // Once deletion starts, finish updating the remote manifest even if the page is closed.
        if (activeToken != null && !deleting) activeToken.cancel();
        super.onCleared();
    }
}
