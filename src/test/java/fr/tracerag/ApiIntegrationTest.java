package fr.tracerag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "tracerag.trace-path=target/test-logs/api-traces.jsonl",
                "tracerag.runtime-dir=target/test-runtime-api"
        })
class ApiIntegrationTest {
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @Test
    void healthAndFrontendAreServedWithSecurityHeaders() throws IOException, InterruptedException {
        HttpResponse<String> health = get("/api/health", "employee");
        HttpResponse<String> frontend = get("/", "employee");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("semantic-lite-java-v1", "\"documents\":5");
        assertThat(frontend.statusCode()).isEqualTo(200);
        assertThat(frontend.body()).contains("TraceRAG Spring");
        assertThat(frontend.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void askEndpointReturnsCitations() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/ask"))
                .header("Content-Type", "application/json")
                .header("X-Role", "employee")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"question\":\"Comment corriger les déconnexions Bluetooth ?\",\"topK\":4}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"passed\"", "tickets-edge-2026q3");
    }

    @Test
    void ingestionRequiresAdmin() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/ingest"))
                .header("Content-Type", "application/json")
                .header("X-Role", "employee")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"documents":[{"id":"new-doc","title":"Nouveau document","kind":"OTHER",
                        "classification":"INTERNAL","content":"Ce contenu est assez long pour passer la validation."}],"replace":false}
                        """))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path, String role) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("X-Role", role)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}

