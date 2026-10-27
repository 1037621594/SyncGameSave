package com.example.syncgamesave.storage;

import android.util.Xml;
import com.amazonaws.DefaultRequest;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.http.HttpMethodName;
import com.example.syncgamesave.model.FileDigest;
import com.example.syncgamesave.model.RemoteFile;
import com.example.syncgamesave.model.S3Config;
import com.example.syncgamesave.sync.CancellationToken;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.xmlpull.v1.XmlPullParser;

/** S3 REST transport with the AWS SDK's SigV4 signer and cancellable OkHttp I/O. */
public class S3Client implements ObjectStorage {
    private static final MediaType BINARY = MediaType.get("application/octet-stream");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final S3Config config;
    private final CancellationToken token;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    public S3Client(S3Config config, CancellationToken token) {
        validate(config);
        this.config = config;
        this.token = token;
    }

    public static void validate(S3Config config) {
        if (config == null || blank(config.getBucket()) || blank(config.getAccessKey()) || blank(config.getSecretKey()))
            throw new IllegalArgumentException("请先填写 S3 Bucket 和访问凭证");
        if (!config.getBucket().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}"))
            throw new IllegalArgumentException("Bucket 名称不合法");
        if (config.getRegion() == null || !config.getRegion().matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}"))
            throw new IllegalArgumentException("请填写服务商提供的 S3 Region");
        HttpUrl url = endpoint(config.getEndpoint());
        if (!config.isPathStyle()) {
            if (url.host().contains(":") || url.host().matches("[0-9.]+"))
                throw new IllegalArgumentException("IP 地址请勾选路径式访问");
            if (!config.getBucket().matches("[a-z0-9][a-z0-9.-]*[a-z0-9]|[a-z0-9]"))
                throw new IllegalArgumentException("域名式访问的 Bucket 只能包含小写字母、数字、点和连字符");
            try { url.newBuilder().host(config.getBucket() + "." + url.host()).build(); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Bucket 与 Endpoint 无法组成有效域名"); }
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    public static HttpUrl endpoint(String input) {
        HttpUrl url = HttpUrl.parse(input == null ? "" : input.trim());
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null || !url.encodedPath().equals("/"))
            throw new IllegalArgumentException("S3 Endpoint 必须是 HTTP 或 HTTPS 地址，可带端口，不可包含 Bucket、路径、查询参数或账号");
        return url;
    }

    // Encode once using S3's RFC 3986 rules, including spaces, plus signs and literal percent signs.
    private static String encode(String value, boolean keepSlash) {
        StringBuilder result = new StringBuilder();
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (keepSlash && c == '/')) result.append((char) c);
            else result.append('%').append(hex[c >>> 4]).append(hex[c & 15]);
        }
        return result.toString();
    }

    private HttpUrl url(String key) {
        HttpUrl base = endpoint(config.getEndpoint());
        HttpUrl.Builder builder = base.newBuilder();
        String path = "/";
        if (config.isPathStyle()) path += encode(config.getBucket(), false) + (key == null ? "" : "/");
        else builder.host(config.getBucket() + "." + base.host());
        if (key != null) {
            PathRules.validateRelative(key);
            path += encode(key, true);
        }
        return builder.encodedPath(path).build();
    }

    private Request signed(Request request, String payloadHash) throws Exception {
        HttpUrl url = request.url();
        DefaultRequest<Void> aws = new DefaultRequest<>("s3");
        // Empty endpoint path: the resource path already contains the exact once-encoded wire path.
        aws.setEndpoint(new URI(url.scheme(), null, url.host(), url.port(), "", null, null));
        aws.setEncodedResourcePath(url.encodedPath());
        aws.setHttpMethod(HttpMethodName.valueOf(request.method()));
        for (int i = 0; i < url.querySize(); i++) aws.addParameter(url.queryParameterName(i), url.queryParameterValue(i));
        for (String name : request.headers().names()) aws.addHeader(name, request.header(name));
        aws.addHeader("x-amz-content-sha256", payloadHash);
        AWS4Signer signer = new AWS4Signer(false) {
            @Override protected String calculateContentHash(com.amazonaws.Request<?> value) {
                // The immutable upload snapshot has already been hashed; never buffer it in the SDK.
                return value.getHeaders().get("x-amz-content-sha256");
            }
            @Override protected void addHostHeader(com.amazonaws.Request<?> value) {
                String host = url.host().contains(":") ? "[" + url.host() + "]" : url.host();
                if (url.port() != (url.isHttps() ? 443 : 80)) host += ":" + url.port();
                value.addHeader("Host", host);
            }
        };
        signer.setServiceName("s3");
        signer.setRegionName(config.getRegion());
        signer.sign(aws, new BasicAWSCredentials(config.getAccessKey(), config.getSecretKey()));
        Request.Builder builder = request.newBuilder();
        for (Map.Entry<String, String> header : aws.getHeaders().entrySet()) builder.header(header.getKey(), header.getValue());
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
                String region = response.header("x-amz-bucket-region", "");
                response.close();
                String hint = code == 301 || code == 307 ? "，请检查最终 Endpoint 和 Region"
                        : code == 401 || code == 403 ? "，请检查凭证、权限、Region 与设备时间"
                        : code == 412 ? "，远端版本已变化，请重试" : "";
                if (!region.isEmpty() && region.matches("[a-zA-Z0-9_-]{1,64}")) hint += "，服务端 Region: " + region;
                throw new IOException("S3 请求失败（HTTP " + code + hint + "）");
            }
            return response;
        } catch (IOException e) {
            token.detach(call);
            token.check();
            if (e.getMessage() != null && e.getMessage().startsWith("S3 ")) throw e;
            throw new IOException("S3 网络请求失败，请检查网络、Endpoint 和证书（HTTPS）");
        }
        // Keep attached while consuming the response so cancellation also interrupts downloads.
    }

    public List<RemoteFile> list(String prefix) throws Exception {
        List<RemoteFile> result = new ArrayList<>();
        Set<String> markers = new HashSet<>();
        Set<String> keys = new HashSet<>();
        String marker = "";
        do {
            token.check();
            // ListObjects V1 is supported by Qiniu and older S3-compatible implementations.
            HttpUrl.Builder builder = url(null).newBuilder()
                    .addEncodedQueryParameter("prefix", encode(prefix, false))
                    .addQueryParameter("max-keys", "1000").addQueryParameter("encoding-type", "url");
            if (!marker.isEmpty()) builder.addEncodedQueryParameter("marker", encode(marker, false));
            List<RemoteFile> page = new ArrayList<>();
            String nextMarker = "";
            String encoding = "";
            boolean truncated = false;
            boolean root = false;
            boolean truncationSeen = false;
            try (Response response = execute(signed(new Request.Builder().url(builder.build()).get().build(), EMPTY_SHA256))) {
                if (response.body() == null) throw new IOException("S3 列举响应为空");
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(response.body().byteStream(), "UTF-8");
                RemoteFile current = null;
                int event;
                while ((event = parser.nextToken()) != XmlPullParser.END_DOCUMENT) {
                    token.check();
                    if (event == XmlPullParser.DOCDECL) throw new IOException("S3 XML 不允许文档类型声明");
                    if (event == XmlPullParser.START_TAG) {
                        String name = parser.getName();
                        if (parser.getDepth() == 1) {
                            root = "ListBucketResult".equals(name);
                            if (!root) throw new IOException("S3 返回的列举格式不正确");
                        } else if (parser.getDepth() == 2 && "Contents".equals(name)) current = new RemoteFile();
                        else if (parser.getDepth() == 3 && current != null) {
                            if ("Key".equals(name)) current.setKey(parser.nextText());
                            else if ("ETag".equals(name)) current.setHash(parser.nextText());
                            else if ("Size".equals(name)) current.setSize(Long.parseLong(parser.nextText()));
                        } else if (parser.getDepth() == 2) {
                            if ("IsTruncated".equals(name)) {
                                String value = parser.nextText();
                                if (!"true".equals(value) && !"false".equals(value)) throw new IOException("S3 分页状态格式错误");
                                truncated = "true".equals(value);
                                truncationSeen = true;
                            } else if ("NextMarker".equals(name)) nextMarker = parser.nextText();
                            else if ("EncodingType".equals(name)) encoding = parser.nextText();
                        }
                    } else if (event == XmlPullParser.END_TAG && parser.getDepth() == 2 && "Contents".equals(parser.getName())) {
                        if (current == null || current.getKey() == null) throw new IOException("S3 对象信息缺少 Key");
                        page.add(current);
                        current = null;
                    }
                }
            }
            if (!root || !truncationSeen || (!encoding.isEmpty() && !"url".equals(encoding)))
                throw new IOException("S3 列举响应缺少分页信息或编码不支持");
            for (RemoteFile file : page) {
                if ("url".equals(encoding)) file.setKey(decode(file.getKey()));
                if (!file.getKey().startsWith(prefix)) throw new IOException("S3 返回了映射范围外的对象");
                if (keys.add(file.getKey())) result.add(file);
            }
            if ("url".equals(encoding)) nextMarker = decode(nextMarker);
            if (truncated && nextMarker.isEmpty() && !page.isEmpty()) nextMarker = page.get(page.size() - 1).getKey();
            if (truncated && (nextMarker.isEmpty() || !markers.add(nextMarker))) throw new IOException("S3 分页标记为空或重复，请重试");
            marker = truncated ? nextMarker : "";
        } while (!marker.isEmpty());
        return result;
    }

    private static String decode(String value) throws Exception {
        // URLDecoder uses form encoding; a literal '+' in an S3 key must remain a plus.
        return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
    }

    public RemoteFile stat(String key) throws Exception {
        try (Response response = execute(signed(new Request.Builder().url(url(key)).head().build(), EMPTY_SHA256))) {
            RemoteFile file = new RemoteFile();
            file.setKey(key);
            file.setHash(response.header("ETag", ""));
            try { file.setSize(Long.parseLong(response.header("Content-Length", "-1"))); }
            catch (NumberFormatException e) { throw new IOException("S3 对象大小格式不正确"); }
            if (file.getHash().isEmpty() || file.getSize() < 0) throw new IOException("S3 对象信息缺少 ETag 或大小");
            try { file.setSourceModified(Math.max(0, Long.parseLong(response.header("x-amz-meta-sgs-mtime", "0")))); }
            catch (NumberFormatException ignored) { file.setSourceModified(0); }
            String sha256 = response.header("x-amz-meta-sgs-sha256", "");
            if (sha256.matches("[0-9a-f]{64}")) file.setContentHash(sha256);
            return file;
        }
    }

    public RemoteFile upload(String key, long sourceModified, FileDigest file) throws Exception {
        if (file.getSize() > 5L * 1024 * 1024 * 1024) throw new IOException("单文件超过 S3 普通上传的 5 GiB 上限，暂不支持分片上传");
        Request request = new Request.Builder().url(url(key))
                .header("Content-Type", "application/octet-stream").header("Content-MD5", file.getMd5())
                .header("x-amz-meta-sgs-mtime", Long.toString(Math.max(0, sourceModified)))
                .header("x-amz-meta-sgs-sha256", file.getSha256())
                .put(RequestBody.create(BINARY, file.getFile())).build();
        String etag;
        try (Response response = execute(signed(request, file.getSha256()))) {
            etag = response.header("ETag", "");
            if (etag.isEmpty()) throw new IOException("S3 上传响应缺少 ETag");
        }
        RemoteFile remote = stat(key);
        if (!etag.equals(remote.getHash()) || remote.getSize() != file.getSize()
                || remote.getSourceModified() != Math.max(0, sourceModified)
                || !file.getSha256().equals(remote.getContentHash()))
            throw new IOException("S3 上传版本、大小或元数据校验失败，请重试");
        return remote;
    }

    public FileDigest download(RemoteFile remote, File destination) throws Exception {
        Request request = new Request.Builder().url(url(remote.getKey())).get()
                .header("If-Match", remote.getHash()).header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "identity").build();
        try (Response response = execute(signed(request, EMPTY_SHA256)); FileOutputStream output = new FileOutputStream(destination)) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("S3 下载响应为空");
            if (!remote.getHash().equals(response.header("ETag"))) throw new IOException("S3 下载版本已变化，请重试");
            TreeFiles.copy(body.byteStream(), output, token);
            output.getFD().sync();
        }
        FileDigest file = Digests.inspect(destination, token);
        if (remote.getSize() != file.getSize()
                || (remote.getContentHash() != null && !remote.getContentHash().equals(file.getSha256())))
            throw new IOException("S3 下载内容校验不一致，请重试");
        return file;
    }

    @Override public void close() {
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }
}
