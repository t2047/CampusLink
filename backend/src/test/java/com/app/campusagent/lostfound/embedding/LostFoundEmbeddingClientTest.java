package com.app.campusagent.lostfound.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LostFoundEmbeddingClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void productionClientUsesHttp11ForUvicornCompatibility() throws Exception {
        AtomicReference<String> protocol = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embed/text", exchange -> {
            protocol.set(exchange.getProtocol());
            byte[] vector = Base64.getEncoder().encode(new byte[]{0, 0, (byte) 128, 63});
            String body = """
                    {"cross_modal_available":false,"items":[{"semantic":{
                    "encoding":"float32-le-base64","dimension":1,
                    "vector":"%s","model":"test-model","revision":"test-revision"},
                    "cross_modal":null}]}
                    """.formatted(new String(vector, StandardCharsets.US_ASCII));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpClient httpClient = LostFoundEmbeddingClient.createHttpClient();
        LostFoundEmbeddingClient client = new LostFoundEmbeddingClient(
                new ObjectMapper(),
                httpClient,
                URI.create("http://127.0.0.1:" + port + "/v1/embed/text"),
                URI.create("http://127.0.0.1:" + port + "/v1/embed/images"),
                "0123456789abcdef",
                "auto",
                Duration.ofSeconds(3));

        Optional<TextEmbeddingBundle> result = client.embedDocument("黑色耳机");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().semantic()).isNotNull();
        assertThat(protocol).hasValue("HTTP/1.1");
        assertThat(httpClient.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }
}
