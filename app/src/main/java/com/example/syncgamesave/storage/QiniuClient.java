package com.example.syncgamesave.storage;

import android.util.Base64;
import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.model.QiniuConfig;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.sync.CancellationToken;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Qiniu REST integration: scoped upload tokens, management v2 signatures and signed downloads. */
public class QiniuClient implements java.io.Closeable {
    private static final MediaType BINARY = MediaType.get("application/octet-stream");
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    private final QiniuConfig config;
    private final CancellationToken token;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    public QiniuClient(QiniuConfig config, CancellationToken token) {
        validate(config);
        this.config = config;
        this.token = token;
    }

    @Override public void close() {
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }

    public static void validate(QiniuConfig config) {
        if (config == null || config.getBucket().trim().isEmpty() || config.getAccessKey().trim().isEmpty()
                || config.getSecretKey().trim().isEmpty()) throw new IllegalArgumentException("请先填写七牛空间和访问凭证");
        if (!config.getBucket().matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("空间名称不合法");
        endpoint(config.getUploadEndpoint());
        endpoint(config.getResourceEndpoint());
        endpoint(config.getListEndpoint());
        endpoint(config.getDownloadDomain());
    }

    public static HttpUrl endpoint(String input) {
        HttpUrl url = HttpUrl.parse(input == null ? "" : input.trim());
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null || !url.encodedPath().equals("/"))
            throw new IllegalArgumentException("服务地址／下载域名必须是 HTTP 或 HTTPS 域名，可带端口，不可包含路径、查询参数或账号");
        return url;
    }

    private static String base64(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(config.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return config.getAccessKey() + ":" + base64(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Request management(HttpUrl url, boolean post) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = format.format(new Date());
        String method = post ? "POST" : "GET";
        String path = url.encodedPath() + (url.encodedQuery() == null ? "" : "?" + url.encodedQuery());
        int defaultPort = url.isHttps() ? 443 : 80;
        String host = (url.host().contains(":") ? "[" + url.host() + "]" : url.host())
                + (url.port() == defaultPort ? "" : ":" + url.port());
        String canonical = method + " " + path + "\nHost: " + host
                + (post ? "\nContent-Type: application/x-www-form-urlencoded" : "")
                + "\nX-Qiniu-Date: " + date + "\n\n";
        Request.Builder builder = new Request.Builder().url(url).header("X-Qiniu-Date", date)
                .header("Authorization", "Qiniu " + sign(canonical));
        if (post) builder.post(RequestBody.create(FORM, new byte[0]));
        return builder.build();
    }

    private Response execute(Request request) throws IOException {
        token.check();
        Call call = http.newCall(request);
        token.attach(call);
        try {
            Response response = call.execute();
            if (!response.isSuccessful()) {
                int code = response.code();
                String requestId = response.header("X-Reqid", "无");
                response.close();
                throw new IOException("七牛请求失败（HTTP " + code + "，请求编号 " + requestId + "）");
            }
            return response;
        } catch (IOException e) {
            token.detach(call);
            token.check();
            if (e.getMessage() != null && e.getMessage().startsWith("七牛请求失败")) throw e;
            throw new IOException("七牛网络请求失败，请检查网络、服务地址及证书（HTTPS）");
        }
        // Keep the call attached until the body has been consumed so cancellation interrupts reads.
    }

    private JsonObject json(Request request) throws IOException {
        try (Response response = execute(request)) {
            if (response.body() == null) throw new IOException("七牛返回空响应");
            try { return JsonParser.parseString(response.body().string()).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("七牛返回的响应格式不正确"); }
        } catch (IOException e) {
            token.check();
            if (e.getMessage() != null && e.getMessage().startsWith("七牛")) throw e;
            throw new IOException("读取七牛响应失败，请检查网络");
        }
    }

    public List<RemoteFile> list(String prefix) throws Exception {
        List<RemoteFile> result = new ArrayList<>();
        Set<String> markers = new HashSet<>();
        Set<String> keys = new HashSet<>();
        String marker = "";
        do {
            token.check();
            HttpUrl.Builder url = endpoint(config.getListEndpoint()).newBuilder().addPathSegment("list")
                    .addQueryParameter("bucket", config.getBucket()).addQueryParameter("prefix", prefix)
                    .addQueryParameter("limit", "1000");
            if (!marker.isEmpty()) url.addQueryParameter("marker", marker);
            JsonObject response = json(management(url.build(), false));
            JsonArray items = response.has("items") && !response.get("items").isJsonNull()
                    ? response.getAsJsonArray("items") : new JsonArray();
            for (JsonElement item : items) {
                RemoteFile file = parse(item.getAsJsonObject());
                if (file.getKey() == null || !file.getKey().startsWith(prefix)) throw new IOException("七牛返回了映射范围外的对象");
                if (keys.add(file.getKey())) result.add(file);
            }
            marker = text(response, "marker");
            if (!marker.isEmpty() && !markers.add(marker)) throw new IOException("七牛分页标记重复，请重试");
        } while (!marker.isEmpty());
        return result;
    }

    public RemoteFile stat(String key) throws Exception {
        String entry = base64((config.getBucket() + ":" + key).getBytes(StandardCharsets.UTF_8));
        HttpUrl url = endpoint(config.getResourceEndpoint()).newBuilder().addPathSegment("stat").addPathSegment(entry).build();
        RemoteFile file = parse(json(management(url, true)));
        file.setKey(key);
        return file;
    }

    private static RemoteFile parse(JsonObject value) throws IOException {
        try {
            RemoteFile result = new RemoteFile();
            result.setKey(text(value, "key"));
            result.setHash(text(value, "hash"));
            result.setSize(value.has("fsize") ? value.get("fsize").getAsLong() : -1);
            result.setUploadedAt(value.has("putTime") ? value.get("putTime").getAsLong() / 10000 : 0);
            if (value.has("x-qn-meta") && value.get("x-qn-meta").isJsonObject()) {
                JsonObject meta = value.getAsJsonObject("x-qn-meta");
                String time = meta(meta, "sgs-mtime");
                try { result.setSourceModified(Math.max(0, Long.parseLong(time))); }
                catch (NumberFormatException ignored) { result.setSourceModified(0); }
                String sha256 = meta(meta, "sgs-sha256");
                if (sha256.matches("[0-9a-f]{64}")) result.setContentHash(sha256);
            }
            return result;
        } catch (RuntimeException e) { throw new IOException("七牛对象信息不完整或格式错误"); }
    }

    private static String meta(JsonObject value, String key) {
        return value.has(key) ? text(value, key) : text(value, "x-qn-meta-" + key);
    }

    private static String text(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    public RemoteFile upload(String key, long sourceModified, FileDigest file) throws Exception {
        JsonObject policy = new JsonObject();
        policy.addProperty("scope", config.getBucket() + ":" + key);
        policy.addProperty("deadline", System.currentTimeMillis() / 1000 + 3600);
        policy.addProperty("insertOnly", 0);
        String encodedPolicy = base64(policy.toString().getBytes(StandardCharsets.UTF_8));
        String uploadToken = sign(encodedPolicy) + ":" + encodedPolicy;
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("key", key).addFormDataPart("token", uploadToken)
                .addFormDataPart("crc32", Long.toString(file.getCrc32()))
                .addFormDataPart("x-qn-meta-sgs-mtime", Long.toString(Math.max(0, sourceModified)))
                .addFormDataPart("x-qn-meta-sgs-sha256", file.getSha256())
                .addFormDataPart("file", "save.bin", RequestBody.create(BINARY, file.getFile())).build();
        JsonObject response = json(new Request.Builder().url(endpoint(config.getUploadEndpoint())).post(body).build());
        if (!key.equals(text(response, "key")) || !file.getEtag().equals(text(response, "hash")))
            throw new IOException("上传返回的文件标识或内容校验不一致");
        RemoteFile remote = stat(key);
        if (!file.getEtag().equals(remote.getHash()) || remote.getSourceModified() != Math.max(0, sourceModified)
                || !file.getSha256().equals(remote.getContentHash()))
            throw new IOException("远端上传版本或元数据校验失败，请重试");
        return remote;
    }

    public FileDigest download(RemoteFile remote, File destination) throws Exception {
        HttpUrl.Builder builder = endpoint(config.getDownloadDomain()).newBuilder();
        for (String segment : remote.getKey().split("/", -1)) builder.addPathSegment(segment);
        // A unique query avoids serving a previous CDN version after an overwrite.
        builder.addQueryParameter("_sgs", remote.getHash() + "-" + System.currentTimeMillis());
        if (config.isPrivateBucket()) builder.addQueryParameter("e", Long.toString(System.currentTimeMillis() / 1000 + 3600));
        String url = builder.build().toString();
        if (config.isPrivateBucket()) url = url + "&token=" + sign(url);
        try (Response response = execute(new Request.Builder().url(url).header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "identity").build()); FileOutputStream output = new FileOutputStream(destination)) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("下载响应为空");
            TreeFiles.copy(body.byteStream(), output, token);
            output.getFD().sync();
        } catch (IOException e) {
            token.check();
            if (e.getMessage() != null && e.getMessage().startsWith("七牛")) throw e;
            throw new IOException("下载失败，请检查网络与本机剩余空间");
        }
        FileDigest file = Digests.inspect(destination, token);
        if (remote.getSize() < 0 || remote.getSize() != file.getSize() || !remote.getHash().equals(file.getEtag())
                || (remote.getContentHash() != null && !remote.getContentHash().equals(file.getSha256())))
            throw new IOException("下载内容与远端版本校验不一致，请重试");
        return file;
    }
}
