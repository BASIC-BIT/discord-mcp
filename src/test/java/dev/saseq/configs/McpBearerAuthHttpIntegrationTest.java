package dev.saseq.configs;

import dev.saseq.DiscordMcpApplication;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DiscordMcpApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("http")
class McpBearerAuthHttpIntegrationTest {
    private static final String TOKEN = "12345678901234567890123456789012";
    private static final Path TOKEN_FILE = createTokenFile();
    private static final String INITIALIZE_REQUEST = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-03-26","capabilities":{},
              "clientInfo":{"name":"auth-integration-test","version":"1"}}}
            """;

    @LocalServerPort
    int port;

    @MockitoBean
    JDA jda;

    @DynamicPropertySource
    static void bearerProperties(DynamicPropertyRegistry registry) {
        registry.add("DISCORD_MCP_ACCESS_TOKEN_FILE", TOKEN_FILE::toString);
    }

    @Test
    void bearerFilterProtectsTheRealStreamableHttpServlet() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
        HttpRequest unauthenticated = request(endpoint).build();
        HttpRequest authenticated = request(endpoint)
                .header("Authorization", "Bearer " + TOKEN)
                .build();

        assertThat(client.send(unauthenticated, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
        assertThat(client.send(authenticated, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isBetween(200, 299);
    }

    private static HttpRequest.Builder request(URI endpoint) {
        return HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_REQUEST));
    }

    private static Path createTokenFile() {
        try {
            Path file = Files.createTempFile("discord-mcp-http-auth-", ".token");
            Files.writeString(file, TOKEN);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
