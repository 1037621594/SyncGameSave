package com.example.syncgamesave.sync;

import com.example.syncgamesave.data.SyncRepository;
import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.model.FileState;
import com.example.syncgamesave.model.LocalFile;
import com.example.syncgamesave.model.PathMapping;
import com.example.syncgamesave.model.RemoteManifest;
import com.example.syncgamesave.model.RemoteManifestEntry;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.model.SyncResult;
import com.example.syncgamesave.storage.ObjectStorage;
import com.example.syncgamesave.storage.TreeFiles;
import com.example.syncgamesave.storage.PathRules;
import com.example.syncgamesave.storage.Digests;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SyncEngine {
    public static final String MANIFEST_PATH = "._syncgamesave/manifest.json";
    private static final int COMPARISON_THREADS = 4;
    private final Gson gson = new Gson();
    public interface Progress { void update(String message, int done, int total); }
    public interface Confirmer {
        boolean confirm(String path, long localTime, long remoteTime, String reason, CancellationToken token) throws Exception;
    }

    private final SyncRepository repository;
    private final TreeFiles files;
    private final ObjectStorage remote;
    private final CancellationToken token;
    private final Progress progress;
    private final Confirmer confirmer;

    public SyncEngine(SyncRepository repository, TreeFiles files, ObjectStorage remote,
                      CancellationToken token, Progress progress, Confirmer confirmer) {
        this.repository = repository;
        this.files = files;
        this.remote = remote;
        this.token = token;
        this.progress = progress;
        this.confirmer = confirmer;
    }

    public SyncResult upload(PathMapping mapping) throws Exception {
        SyncResult result = new SyncResult();
        result.setDirection("upload");
        RemoteManifest manifest = loadManifest(mapping, true);
        long cutoff = System.currentTimeMillis();
        progress.update("正在扫描本地目录", 0, 0);
        List<LocalFile> locals = files.scan(mapping, token);
        int index = 0;
        for (LocalFile scanned : locals) {
            if (token.isCancelled()) break;
            String path = scanned.getRelativePath();
            progress.update("正在上传：" + path, index, locals.size());
            FileDigest snapshot = null;
            try {
                if (MANIFEST_PATH.equals(path)) throw new IOException("本地路径与远端同步清单保留路径冲突");
                token.check();
                LocalFile local = files.find(mapping, path);
                if (local == null) throw new IOException("扫描后文件已被删除");
                if (local.getModified() > cutoff) {
                    result.setDeferred(result.getDeferred() + 1);
                    result.addMessage(path + "：扫描截止后发生修改，留待下次上传（未来时间请检查设备时钟）");
                    continue;
                }
                FileState previous = repository.file(mapping.getId(), path);
                // A per-file success baseline handles partial retries and the watermark boundary.
                // Changed/new files are uploaded even if their timestamps were moved backwards.

                if (unchangedMetadata(local, previous) && !"download".equals(previous.getDirection())) {
                    result.setUnchanged(result.getUnchanged() + 1);
                    continue;
                }
                snapshot = files.snapshot(local, token);
                if (!TreeFiles.same(local, files.find(mapping, path))) throw new IOException("读取期间文件发生变化，请重试");
                if (previous != null && !previous.isFailed() && "download".equals(previous.getDirection())
                        && snapshot.getSha256().equals(previous.getContentHash())) {
                    result.setUnchanged(result.getUnchanged() + 1);
                    continue;
                }
                RemoteFile uploaded = remote.upload(mapping.getRemotePrefix() + path, local.getModified(), snapshot);
                LocalFile after = files.find(mapping, path);
                if (!TreeFiles.same(local, after)) throw new IOException("上传期间文件发生变化，已保留为待重试");
                FileDigest check = files.snapshot(after, token);
                try {
                    if (!snapshot.getSha256().equals(check.getSha256()) || !TreeFiles.same(after, files.find(mapping, path)))
                        throw new IOException("上传期间内容发生变化，已保留为待重试");
                } finally { check.getFile().delete(); }
                manifest.getFiles().put(path, entry(uploaded));
                saveManifest(mapping, manifest);
                saveState(mapping, local, uploaded, snapshot.getSha256(), "upload");
                result.setSuccess(result.getSuccess() + 1);
            } catch (Exception e) {
                if (token.isCancelled()) break;
                fail(mapping, path, result, e);
            } finally {
                if (snapshot != null) snapshot.getFile().delete();
                index++;
                progress.update("已处理 " + index + "/" + locals.size(), index, locals.size());
            }
        }
        result.setCancelled(token.isCancelled());
        if (!result.isCancelled() && result.getFailed() == 0) mapping.setLastUploadTime(cutoff);
        if (locals.isEmpty()) result.addMessage("没有需要同步的文件");
        return result;
    }

    public SyncResult download(PathMapping mapping) throws Exception {
        SyncResult result = new SyncResult();
        result.setDirection("download");
        progress.update("正在读取远端文件列表", 0, 0);
        files.root(mapping);
        RemoteManifest manifest = loadManifest(mapping, true);
        List<RemoteFile> objects = manifestObjects(mapping, manifest);
        Map<String, FileState> states = repository.files(mapping.getId());
        List<RemoteFile> candidates = compareRemoteFiles(mapping, objects, states, result);
        int index = 0;
        for (RemoteFile listed : candidates) {
            if (token.isCancelled()) break;
            String path = listed.getKey().substring(mapping.getRemotePrefix().length());
            try {
                PathRules.validateRelative(path);
                if (!PathRules.matches(mapping, path)) continue;
                progress.update("正在下载：" + path, index, candidates.size());
                downloadOne(mapping, path, listed.getKey(), listed, states.get(path), result);
            } catch (Exception e) {
                if (token.isCancelled()) break;
                fail(mapping, path, result, e);
            } finally {
                index++;
                progress.update("下载已处理 " + index + "/" + candidates.size(), index, candidates.size());
            }
        }
        result.setCancelled(token.isCancelled());
        if (result.getSuccess() + result.getUnchanged() + result.getSkipped() + result.getFailed() == 0)
            result.addMessage("远端没有符合当前配置的文件");
        return result;
    }

    /** Bounded batches keep directory queries parallel without queuing the entire manifest. */
    private List<RemoteFile> compareRemoteFiles(PathMapping mapping, List<RemoteFile> objects,
                                                Map<String, FileState> states, SyncResult result) throws Exception {
        List<RemoteFile> candidates = new ArrayList<>();
        if (objects.isEmpty() || token.isCancelled()) return candidates;
        ExecutorService comparisons = Executors.newFixedThreadPool(Math.min(COMPARISON_THREADS, objects.size()));
        progress.update("正在并行对比远端清单", 0, objects.size());
        try {
            for (int start = 0; start < objects.size() && !token.isCancelled(); start += COMPARISON_THREADS) {
                int end = Math.min(start + COMPARISON_THREADS, objects.size());
                List<Future<Boolean>> pending = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    RemoteFile listed = objects.get(i);
                    String path = listed.getKey().substring(mapping.getRemotePrefix().length());
                    pending.add(comparisons.submit(() -> {
                        token.check();
                        boolean unchanged = unchangedRemote(mapping, path, listed, states.get(path));
                        token.check();
                        return unchanged;
                    }));
                }
                for (int i = start; i < end; i++) {
                    if (token.isCancelled()) return candidates;
                    RemoteFile listed = objects.get(i);
                    String path = listed.getKey().substring(mapping.getRemotePrefix().length());
                    try {
                        Future<Boolean> comparison = pending.get(i - start);
                        boolean unchanged;
                        while (true) {
                            if (token.isCancelled()) return candidates;
                            try { unchanged = comparison.get(200, TimeUnit.MILLISECONDS); break; }
                            catch (TimeoutException ignored) { /* Keep cancellation responsive. */ }
                        }
                        // Result accounting and all writes stay on the coordinator thread.
                        if (unchanged) result.setUnchanged(result.getUnchanged() + 1);
                        else candidates.add(listed);
                    } catch (ExecutionException e) {
                        if (token.isCancelled()) return candidates;
                        Throwable cause = e.getCause();
                        if (!(cause instanceof Exception)) throw e;
                        fail(mapping, path, result, (Exception) cause);
                    }
                }
                progress.update("清单对比 " + end + "/" + objects.size() + "，待下载 " + candidates.size(), end, objects.size());
            }
        } finally {
            comparisons.shutdownNow();
        }
        return candidates;
    }

    private void downloadOne(PathMapping mapping, String path, String key, RemoteFile listed,
                             FileState previous, SyncResult result) throws Exception {
        RemoteFile version = listed;
        if (version.getSourceModified() <= 0 || version.getContentHash() == null) {
            version = remote.stat(key);
            if (!Objects.equals(listed.getHash(), version.getHash()) || listed.getSize() != version.getSize())
                throw new IOException("下载前远端版本已变化，请重新下载");
        }
        File temporary = files.newDownload();
        try {
            // Fetch and validate the remote version before any local destructive operation.
            FileDigest downloaded = remote.download(version, temporary);
            for (int attempt = 0; attempt < 3; attempt++) {
                token.check();
                LocalFile local = files.find(mapping, path);
                String reviewedHash = null;
                if (local != null) {
                    FileDigest localDigest = files.snapshot(local, token);
                    try {
                        reviewedHash = localDigest.getSha256();
                        if (!TreeFiles.same(local, files.find(mapping, path))) continue;
                        if (downloaded.getSha256().equals(reviewedHash)) {
                            saveState(mapping, local, version, reviewedHash, "download");
                            result.setUnchanged(result.getUnchanged() + 1);
                            return;
                        }
                    } finally { localDigest.getFile().delete(); }
                    long localTime = local.getModified();
                    if (previous != null && !previous.isFailed() && "download".equals(previous.getDirection())
                            && reviewedHash.equals(previous.getContentHash()) && local.getModified() == previous.getLocalModified())
                        localTime = previous.getSourceModified();
                    long remoteTime = version.getSourceModified();
                    String reason = null;
                    if (localTime <= 0 || remoteTime <= 0) reason = "无法可靠判断文件新旧，是否覆盖本地存档？";
                    else if (remoteTime < localTime) reason = "远端文件早于本地文件，覆盖可能丢失较新的存档。";
                    else if (remoteTime == localTime) reason = "文件更新时间相同但内容不同，是否覆盖本地存档？";
                    if (reason != null) {
                        progress.update("等待覆盖确认：" + path, 0, 0);
                        if (!confirmer.confirm(path, localTime, remoteTime, reason, token)) {
                            result.setSkipped(result.getSkipped() + 1);
                            result.addMessage(path + "：用户跳过覆盖");
                            return;
                        }
                    }
                    // Recheck both metadata and bytes after an arbitrarily long confirmation dialog.
                    LocalFile latest = files.find(mapping, path);
                    if (!TreeFiles.same(local, latest)) continue;
                    FileDigest latestDigest = files.snapshot(latest, token);
                    try { if (!reviewedHash.equals(latestDigest.getSha256())) continue; }
                    finally { latestDigest.getFile().delete(); }
                } else if (files.find(mapping, path) != null) continue;
                RemoteFile latestRemote = remote.stat(key);
                if (!Objects.equals(version.getHash(), latestRemote.getHash())
                        || version.getSourceModified() != latestRemote.getSourceModified()
                        || !Objects.equals(version.getContentHash(), latestRemote.getContentHash()))
                    throw new IOException("下载期间远端版本发生变化，请重新下载");
                LocalFile written = files.replace(mapping, path, local, reviewedHash, downloaded, token);
                saveState(mapping, written, version, downloaded.getSha256(), "download");
                result.setSuccess(result.getSuccess() + 1);
                return;
            }
            throw new IOException("本地文件持续变化，请暂停游戏后重试下载");
        } finally { temporary.delete(); }
    }

    private boolean unchangedMetadata(LocalFile local, FileState state) {
        return state != null && !state.isFailed() && local.getModified() > 0 && local.getSize() >= 0
                && local.getModified() == state.getLocalModified() && local.getSize() == state.getLocalSize();
    }

    private boolean unchangedRemote(PathMapping mapping, String path, RemoteFile listed, FileState state) throws IOException {
        if (state == null || state.isFailed() || state.getRemoteHash() == null
                || !Objects.equals(state.getRemoteHash(), listed.getHash())) return false;
        long knownRemoteSize = state.isRemoteSizeKnown() ? state.getRemoteSize() : state.getLocalSize();
        if (knownRemoteSize != listed.getSize()) return false;
        // A local edit does not make an unchanged remote object a download candidate.
        return files.find(mapping, path) != null;
    }

    private void saveState(PathMapping mapping, LocalFile local, RemoteFile version, String contentHash, String direction) {
        FileState state = new FileState();
        state.setRelativePath(local.getRelativePath());
        state.setLocalModified(local.getModified());
        state.setLocalSize(local.getSize());
        state.setSourceModified(version.getSourceModified());
        state.setRemoteHash(version.getHash());
        state.setRemoteSize(version.getSize());
        state.setRemoteSizeKnown(true);
        state.setContentHash(contentHash);
        state.setDirection(direction);
        repository.saveFile(mapping.getId(), state);
    }

    private String manifestKey(PathMapping mapping) { return mapping.getRemotePrefix() + MANIFEST_PATH; }

    private RemoteManifest loadManifest(PathMapping mapping, boolean bootstrap) throws Exception {
        String key = manifestKey(mapping);
        RemoteFile remoteManifest = null;
        for (RemoteFile object : remote.list(key)) if (key.equals(object.getKey())) { remoteManifest = object; break; }
        if (remoteManifest != null) {
            File temporary = files.newDownload();
            try {
                remote.download(remoteManifest, temporary);
                RemoteManifest manifest;
                try (InputStreamReader input = new InputStreamReader(new FileInputStream(temporary), StandardCharsets.UTF_8)) {
                    manifest = gson.fromJson(input, RemoteManifest.class);
                }
                if (manifest == null || manifest.getVersion() != 1 || manifest.getFiles() == null)
                    throw new IOException("远端同步清单格式不受支持");
                for (String path : manifest.getFiles().keySet()) {
                    PathRules.validateRelative(path);
                    if (MANIFEST_PATH.equals(path)) throw new IOException("远端同步清单包含保留路径");
                }
                return manifest;
            } finally { temporary.delete(); }
        }
        RemoteManifest manifest = new RemoteManifest();
        if (bootstrap) {
            for (RemoteFile object : remote.list(mapping.getRemotePrefix())) {
                String path = object.getKey().substring(mapping.getRemotePrefix().length());
                if (path.isEmpty() || path.endsWith("/") || MANIFEST_PATH.equals(path)) continue;
                try { PathRules.validateRelative(path); }
                catch (IllegalArgumentException ignored) { continue; }
                manifest.getFiles().put(path, entry(object));
            }
            if (!manifest.getFiles().isEmpty()) saveManifest(mapping, manifest);
        }
        return manifest;
    }

    private List<RemoteFile> manifestObjects(PathMapping mapping, RemoteManifest manifest) throws IOException {
        List<RemoteFile> objects = new ArrayList<>();
        for (Map.Entry<String, RemoteManifestEntry> item : manifest.getFiles().entrySet()) {
            String path = item.getKey();
            PathRules.validateRelative(path);
            if (!PathRules.matches(mapping, path)) continue;
            RemoteManifestEntry entry = item.getValue();
            if (entry == null || entry.getEtag() == null || entry.getEtag().isEmpty() || entry.getSize() < 0)
                throw new IOException("远端同步清单中的对象信息不完整：" + path);
            RemoteFile object = new RemoteFile();
            object.setKey(mapping.getRemotePrefix() + path);
            object.setHash(entry.getEtag());
            object.setSize(entry.getSize());
            object.setSourceModified(entry.getSourceModified());
            object.setContentHash(entry.getSha256());
            objects.add(object);
        }
        return objects;
    }

    private RemoteManifestEntry entry(RemoteFile object) {
        RemoteManifestEntry entry = new RemoteManifestEntry();
        entry.setEtag(object.getHash());
        entry.setSize(object.getSize());
        entry.setSourceModified(object.getSourceModified());
        entry.setSha256(object.getContentHash());
        return entry;
    }

    private void saveManifest(PathMapping mapping, RemoteManifest manifest) throws Exception {
        File temporary = files.newDownload();
        try {
            byte[] json = gson.toJson(manifest).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(json);
                output.getFD().sync();
            }
            FileDigest digest = Digests.inspect(temporary, token);
            remote.upload(manifestKey(mapping), System.currentTimeMillis(), digest);
        } finally { temporary.delete(); }
    }

    private void fail(PathMapping mapping, String path, SyncResult result, Exception error) {
        FileState state = repository.file(mapping.getId(), path);
        if (state == null) { state = new FileState(); state.setRelativePath(path); }
        state.setFailed(true);
        state.setError(message(error));
        repository.saveFile(mapping.getId(), state);
        result.setFailed(result.getFailed() + 1);
        result.addMessage(path + "：" + message(error));
    }

    public static String message(Exception error) {
        if (error instanceof SecurityException) return "本地访问授权失效，请重新选择目录";
        String message = error.getMessage();
        // Do not expose signed URLs or provider exception text containing credentials.
        if (message == null || message.contains("http:") || message.contains("https:") || message.contains("token="))
            return "操作失败（" + error.getClass().getSimpleName() + "），请检查配置和访问权限";
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}


