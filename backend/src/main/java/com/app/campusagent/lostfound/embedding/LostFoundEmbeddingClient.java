/**
 * 对独立预训练向量模型服务的容错 HTTP 客户端。
 * <p>负责把失物招领的报告文本描述与上传图片发送给独立的嵌入服务
 * （FastAPI/Uvicorn 实现，默认 http://localhost:8091），换取语义向量与
 * 跨模态向量：文本向量用于语义相似检索，图片向量用于以图搜物。
 * <p>设计原则是「任何故障都不抛出异常」：文本失败返回 Optional.empty()、
 * 图片失败返回空列表，由业务层降级为基础匹配 / 颜色指纹，保证搜索主链路可用。
 * <p>依赖外部系统：嵌入服务的 {@code /v1/embed/text} 与 {@code /v1/embed/images}
 * 两个端点；通过 {@code app.lost-found.embedding.*} 配置连接参数与共享密钥。
 */
package com.app.campusagent.lostfound.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 对独立预训练模型服务的容错客户端；任何故障均返回 empty，由业务层降级。 */
@Service
public class LostFoundEmbeddingClient {

    /** SLF4J 日志器，用于记录降级告警。 */
    private static final Logger log = LoggerFactory.getLogger(LostFoundEmbeddingClient.class);

    /** 嵌入服务响应体大小上限（10 MiB），防止异常响应把内存打爆。 */
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    /** Jackson 对象映射器，用于构造 / 解析 JSON 请求与响应。 */
    private final ObjectMapper objectMapper;

    /** Java 原生 HTTP 客户端（强制 HTTP/1.1，见 createHttpClient）。 */
    private final HttpClient httpClient;

    /** 文本嵌入端点 URI：{base}/v1/embed/text。 */
    private final URI textUri;

    /** 图片嵌入端点 URI：{base}/v1/embed/images。 */
    private final URI imageUri;

    /** 与嵌入服务约定的共享密钥，经请求头 X-Embedding-Service-Key 携带。 */
    private final String sharedSecret;

    /** 运行模式：auto 启用；baseline 禁用（降级），由配置 app.lost-found.embedding.mode 决定。 */
    private final String mode;

    /** 单次请求超时时长，由配置 app.lost-found.embedding.timeout-seconds 换算而来。 */
    private final Duration timeout;

    /**
     * 图片输入载体（record）：把一张待嵌入图片的字节、类型与文件名封装在一起。
     * <p>对字节数组做防御性拷贝（构造与读取都返回克隆），
     * 避免外部可变引用修改内部状态，保证 record 的不可变语义。
     */
    public record ImageInput(byte[] content, String contentType, String filename) {
        /** 紧凑构造器：把入参 content 拷贝一份保存，防止外部数组后续被修改影响本对象。 */
        public ImageInput {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            // 访问器同样返回克隆：外部修改返回数组不会污染内部数据
            return content == null ? null : content.clone();
        }
    }

    /**
     * Spring 注入的主构造器：从配置读取嵌入服务参数并组装内部状态。
     *
     * @param baseUrl        嵌入服务根地址；配置项 {@code app.lost-found.embedding.url}，缺省 http://localhost:8091
     * @param sharedSecret   共享密钥；配置项 {@code app.lost-found.embedding.shared-secret}，缺省为空
     * @param mode           运行模式；配置项 {@code app.lost-found.embedding.mode}，缺省 auto
     * @param timeoutSeconds 请求超时秒数；配置项 {@code app.lost-found.embedding.timeout-seconds}，缺省 8
     */
    @Autowired
    public LostFoundEmbeddingClient(
            @Value("${app.lost-found.embedding.url:http://localhost:8091}") String baseUrl,
            @Value("${app.lost-found.embedding.shared-secret:}") String sharedSecret,
            @Value("${app.lost-found.embedding.mode:auto}") String mode,
            @Value("${app.lost-found.embedding.timeout-seconds:8}") long timeoutSeconds) {
        this(
                new ObjectMapper(),
                createHttpClient(),
                // 去掉 baseUrl 末尾多余的 "/" 再拼接文本端点路径，避免出现双斜杠
                URI.create(baseUrl.replaceAll("/+$", "") + "/v1/embed/text"),
                // 同上：拼接图片端点路径
                URI.create(baseUrl.replaceAll("/+$", "") + "/v1/embed/images"),
                sharedSecret,
                mode,
                Duration.ofSeconds(timeoutSeconds));
    }

    /** Uvicorn 不支持 Java HttpClient 默认发起的明文 HTTP/2（h2c）升级。 */
    static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))   // 建连超时：快速失败，避免请求长期挂起
                .version(HttpClient.Version.HTTP_1_1)    // 见上方注释：强制 HTTP/1.1，规避 h2c 升级失败
                .build();
    }

    /**
     * 包级构造器（主要供测试注入 Mock 对象）：
     * 允许把 ObjectMapper、HttpClient、两个 URI、密钥、模式与超时全部显式传入。
     */
    LostFoundEmbeddingClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            URI textUri,
            URI imageUri,
            String sharedSecret,
            String mode,
            Duration timeout) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.textUri = textUri;
        this.imageUri = imageUri;
        this.sharedSecret = sharedSecret;
        this.mode = mode;
        this.timeout = timeout;
    }

    /**
     * 为一段报告文本生成向量，返回语义向量 + 跨模态向量。
     * <p>运行在非事务上下文中（NOT_SUPPORTED），避免调用嵌入服务的耗时占用数据库连接。
     * 任何异常都会被捕获并降级返回 empty，由业务层回退到基础匹配。
     *
     * @param text 报告描述文本
     * @return 包装了 semantic / crossModal 向量的 {@link TextEmbeddingBundle}；失败或未启用时为 empty
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<TextEmbeddingBundle> embedDocument(String text) {
        // 未启用嵌入能力、文本为空或全空白时直接返回 empty，不发起网络请求
        if (!enabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            // 构造请求体：items 为单元素（role=document 表示主文档），
            // 同时请求 semantic（语义）与 cross_modal（跨模态）两个向量空间
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "items", List.of(Map.of("id", "report", "text", text, "role", "document")),
                    "spaces", List.of("semantic", "cross_modal")));
            // 发送 POST 并解析响应 JSON 根节点
            JsonNode root = postJson(textUri, body);
            // 取第一个（也是唯一一个）item
            JsonNode item = root.path("items").path(0);
            // 分别解码 semantic 与 cross_modal 向量；cross_modal_available 标识该模型是否支持跨模态
            return Optional.of(new TextEmbeddingBundle(
                    decode(item.path("semantic")),
                    decode(item.path("cross_modal")),
                    root.path("cross_modal_available").asBoolean(false)));
        } catch (Exception exception) {
            // 记录告警并降级：语义搜索将回退到基础（关键词）匹配
            log.warn("失物招领文本向量生成失败，已降级基础匹配: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 为一组图片生成向量列表（顺序与入参一致）。
     * <p>运行在非事务上下文中；任何异常均被捕获并返回空列表，
     * 由业务层降级为颜色指纹匹配。注意 InterruptedException 会恢复中断标志。
     *
     * @param images 待嵌入图片列表（ImageInput 含字节 / 类型 / 文件名）
     * @return 与入参顺序一一对应的 {@link StoredEmbedding} 列表；失败时为空列表
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoredEmbedding> embedImages(List<ImageInput> images) {
        // 未启用或无图片时直接返回空列表
        if (!enabled() || images == null || images.isEmpty()) {
            return List.of();
        }
        // 随机 boundary 分隔串，保证 multipart 请求体各部分边界唯一，不与内容冲突
        String boundary = "CampusLink-" + UUID.randomUUID();
        try {
            // 手工拼接 multipart/form-data 请求体（见 multipart 方法）
            byte[] body = multipart(images, boundary);
            HttpRequest request = baseRequest(imageUri)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            // 同步发送请求并等待响应字节
            HttpResponse<byte[]> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            // 非 2xx 状态码视为失败
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("embedding service returned " + response.statusCode());
            }
            // 响应过大视为异常，防止极端数据导致内存溢出
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new IOException("embedding service response is too large");
            }
            // 解析 JSON，逐条解码 items 中的 embedding 向量
            JsonNode items = objectMapper.readTree(response.body()).path("items");
            List<StoredEmbedding> result = new ArrayList<>();
            for (JsonNode item : items) {
                result.add(decode(item.path("embedding")));
            }
            return result;
        } catch (Exception exception) {
            // 若线程在阻塞等待期间被中断，需恢复中断标志以遵守协作式取消约定
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 记录告警并降级：以图搜物将回退到颜色指纹匹配
            log.warn("失物招领图片向量生成失败，已降级颜色指纹: {}", exception.getMessage());
            return List.of();
        }
    }

    /**
     * 判断嵌入能力是否启用：要求 mode 非 baseline，且共享密钥非空且长度 >= 16。
     * <p>shared-secret 长度下限是安全约定，防止误配弱密钥；未启用时
     * 上层应走降级匹配路径，不调用嵌入服务。
     */
    public boolean enabled() {
        return !"baseline".equalsIgnoreCase(mode)
                && sharedSecret != null
                && sharedSecret.length() >= 16;
    }

    /** 发送 JSON 请求并解析响应为 JsonNode；非 2xx 或超限响应均抛 IOException。 */
    private JsonNode postJson(URI uri, byte[] body) throws IOException, InterruptedException {
        HttpRequest request = baseRequest(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("embedding service returned " + response.statusCode());
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new IOException("embedding service response is too large");
        }
        return objectMapper.readTree(response.body());
    }

    /** 构造带共享密钥头与超时的通用请求构建器（供文本 / 图片请求复用）。 */
    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)                                 // 总超时：防止慢服务拖垮请求线程
                .header("X-Embedding-Service-Key", sharedSecret); // 鉴权头：嵌入服务端据此校验身份
    }

    /**
     * 从响应 JSON 解码单个 StoredEmbedding。
     * <p>校验链路：encoding 必须为 float32-le-base64 → dimension 合法（1..2048）→
     * Base64 解码后的字节数必须等于 dimension*4（float32 占 4 字节），
     * 任一条件不满足即抛异常触发上层降级，防止脏数据进入检索。
     *
     * @param node 形如 {"encoding":..., "dimension":..., "vector":<base64>, "model":..., "revision":...} 的 JSON 节点
     * @return 解码后的 StoredEmbedding；节点缺失或为 null 时返回 null
     */
    private static StoredEmbedding decode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!"float32-le-base64".equals(node.path("encoding").asText())) {
            throw new IllegalArgumentException("unsupported embedding encoding");
        }
        int dimension = node.path("dimension").asInt();
        // dimension 上限 2048 与模型输出维度上限匹配，用于防御异常响应
        if (dimension <= 0 || dimension > 2048) {
            throw new IllegalArgumentException("invalid embedding dimension");
        }
        // 服务端返回的是 Base64 编码的原始 float32 小端字节，先解码回字节数组
        byte[] vector = Base64.getDecoder().decode(node.path("vector").asText());
        // 字节数必须精确等于 dimension * 4，防止维度信息与数据长度不一致
        if (vector.length != dimension * Float.BYTES) {
            throw new IllegalArgumentException("embedding length does not match dimension");
        }
        // 封装为 StoredEmbedding：数据库直接存原始 float32 字节，对外传输时才 Base64 化
        return new StoredEmbedding(
                vector,
                node.path("model").asText(),
                node.path("revision").asText(),
                dimension);
    }

    /**
     * 手工构造 multipart/form-data 请求体（每张图片一个 part，字段名 images）。
     * <p>Java HttpClient 没有内置 multipart 构建器，故按 RFC 2046 手动拼接：
     * 每个 part 依次为 boundary 行、Content-Disposition、Content-Type、
     * 空行与文件二进制内容，part 之间用 {@code \r\n} 分隔。
     */
    private static byte[] multipart(List<ImageInput> images, String boundary) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (ImageInput image : images) {
            // 每个 part 以 "--<boundary>\r\n" 开头
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            // Content-Disposition：字段名 images，文件名用安全化后的名称（防注入 / 路径穿越）
            output.write(("Content-Disposition: form-data; name=\"images\"; filename=\""
                    + safeName(image.filename()) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            // 再写 Content-Type 头与一个空行，然后紧跟文件二进制内容
            output.write(("Content-Type: " + image.contentType() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.write(image.content());
            // part 之间用 \r\n 分隔
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        // 结尾以 "--<boundary>--\r\n" 收束整个请求体
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    /**
     * 文件名安全化：仅保留字母、数字、点、下划线、连字符，
     * 其余字符一律替换为下划线，避免 multipart 头注入或路径穿越风险。
     */
    private static String safeName(String value) {
        if (value == null) {
            return "image";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
