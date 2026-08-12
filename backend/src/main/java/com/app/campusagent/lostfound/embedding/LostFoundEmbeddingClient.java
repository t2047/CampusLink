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

    private static final Logger log = LoggerFactory.getLogger(LostFoundEmbeddingClient.class);
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI textUri;
    private final URI imageUri;
    private final String sharedSecret;
    private final String mode;
    private final Duration timeout;

    public record ImageInput(byte[] content, String contentType, String filename) {
        public ImageInput {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    @Autowired
    public LostFoundEmbeddingClient(
            @Value("${app.lost-found.embedding.url:http://localhost:8091}") String baseUrl,
            @Value("${app.lost-found.embedding.shared-secret:}") String sharedSecret,
            @Value("${app.lost-found.embedding.mode:auto}") String mode,
            @Value("${app.lost-found.embedding.timeout-seconds:8}") long timeoutSeconds) {
        this(
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                URI.create(baseUrl.replaceAll("/+$", "") + "/v1/embed/text"),
                URI.create(baseUrl.replaceAll("/+$", "") + "/v1/embed/images"),
                sharedSecret,
                mode,
                Duration.ofSeconds(timeoutSeconds));
    }

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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<TextEmbeddingBundle> embedDocument(String text) {
        if (!enabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "items", List.of(Map.of("id", "report", "text", text, "role", "document")),
                    "spaces", List.of("semantic", "cross_modal")));
            JsonNode root = postJson(textUri, body);
            JsonNode item = root.path("items").path(0);
            return Optional.of(new TextEmbeddingBundle(
                    decode(item.path("semantic")),
                    decode(item.path("cross_modal")),
                    root.path("cross_modal_available").asBoolean(false)));
        } catch (Exception exception) {
            log.warn("失物招领文本向量生成失败，已降级基础匹配: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoredEmbedding> embedImages(List<ImageInput> images) {
        if (!enabled() || images == null || images.isEmpty()) {
            return List.of();
        }
        String boundary = "CampusLink-" + UUID.randomUUID();
        try {
            byte[] body = multipart(images, boundary);
            HttpRequest request = baseRequest(imageUri)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
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
            JsonNode items = objectMapper.readTree(response.body()).path("items");
            List<StoredEmbedding> result = new ArrayList<>();
            for (JsonNode item : items) {
                result.add(decode(item.path("embedding")));
            }
            return result;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("失物招领图片向量生成失败，已降级颜色指纹: {}", exception.getMessage());
            return List.of();
        }
    }

    public boolean enabled() {
        return !"baseline".equalsIgnoreCase(mode)
                && sharedSecret != null
                && sharedSecret.length() >= 16;
    }

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

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("X-Embedding-Service-Key", sharedSecret);
    }

    private static StoredEmbedding decode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!"float32-le-base64".equals(node.path("encoding").asText())) {
            throw new IllegalArgumentException("unsupported embedding encoding");
        }
        int dimension = node.path("dimension").asInt();
        if (dimension <= 0 || dimension > 2048) {
            throw new IllegalArgumentException("invalid embedding dimension");
        }
        byte[] vector = Base64.getDecoder().decode(node.path("vector").asText());
        if (vector.length != dimension * Float.BYTES) {
            throw new IllegalArgumentException("embedding length does not match dimension");
        }
        return new StoredEmbedding(
                vector,
                node.path("model").asText(),
                node.path("revision").asText(),
                dimension);
    }

    private static byte[] multipart(List<ImageInput> images, String boundary) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (ImageInput image : images) {
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Disposition: form-data; name=\"images\"; filename=\""
                    + safeName(image.filename()) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Type: " + image.contentType() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.write(image.content());
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static String safeName(String value) {
        if (value == null) {
            return "image";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
