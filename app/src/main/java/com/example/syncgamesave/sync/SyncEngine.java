package com.example.syncgamesave.sync;

import com.example.syncgamesave.data.SyncRepository;
import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.model.FileState;
import com.example.syncgamesave.model.LocalFile;
import com.example.syncgamesave.model.PathMapping;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.model.SyncResult;
import com.example.syncgamesave.storage.QiniuClient;
import com.example.syncgamesave.storage.TreeFiles;
import com.example.syncgamesave.storage.PathRules;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class SyncEngine {
    public interface Progress { void update(String message, int done, int total); }
    public interface Confirmer {
        boolean confirm(String path, long localTime, long remoteTime, String reason, CancellationToken token) throws Exception;
    }

    private final SyncRepository repository;
    private final TreeFiles files;
    private final QiniuClient remote;
    private final CancellationToken token;
    private final Progress progress;
    private final Confirmer confirmer;

    public SyncEngine(SyncRepository repository, TreeFiles files, QiniuClient remote,
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
        List<RemoteFile> objects = remote.list(mapping.getRemotePrefix());
        int index = 0;
        for (RemoteFile listed : objects) {
            if (token.isCancelled()) break;
            String path = listed.getKey().substring(mapping.getRemotePrefix().length());
            if (path.isEmpty() || path.endsWith("/")) continue;
            try {
                PathRules.validateRelative(path);
                if (!PathRules.matches(mapping, path)) continue;
                progress.update("正在下载：" + path, index, objects.size());
                downloadOne(mapping, path, listed.getKey(), result);
            } catch (Exception e) {
                if (token.isCancelled()) break;
                fail(mapping, path, result, e);
            } finally {
                index++;
                progress.update("已处理 " + index + "/" + objects.size(), index, objects.size());
            }
        }
        result.setCancelled(token.isCancelled());
        if (result.getSuccess() + result.getUnchanged() + result.getSkipped() + result.getFailed() == 0)
            result.addMessage("远端没有符合当前配置的文件");
        return result;
    }

    private void downloadOne(PathMapping mapping, String path, String key, SyncResult result) throws Exception {
        RemoteFile version = remote.stat(key);
        File temporary = files.newDownload();
        try {
            // Fetch and validate the remote version before any local destructive operation.
            FileDigest downloaded = remote.download(version, temporary);
            for (int attempt = 0; attempt < 3; attempt++) {
                token.check();
                LocalFile local = files.find(mapping, path);
                FileState previous = repository.file(mapping.getId(), path);
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

    private void saveState(PathMapping mapping, LocalFile local, RemoteFile version, String contentHash, String direction) {
        FileState state = new FileState();
        state.setRelativePath(local.getRelativePath());
        state.setLocalModified(local.getModified());
        state.setLocalSize(local.getSize());
        state.setSourceModified(version.getSourceModified());
        state.setRemoteHash(version.getHash());
        state.setContentHash(contentHash);
        state.setDirection(direction);
        repository.saveFile(mapping.getId(), state);
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


