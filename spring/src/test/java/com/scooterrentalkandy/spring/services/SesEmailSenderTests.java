package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SES sender through the official AWS SDK v2 against a local stub of the SES v2 API on 127.0.0.1: the SDK's
 * real request signing, JSON encoding and error handling are exercised, and nothing leaves the machine. The
 * credentials are placeholders that only the stub sees.
 */
class SesEmailSenderTests {

    private static final String FROM = "no-reply@example.test";
    private static final String TO = "rider@example.test";

    private record Request(String method, String path, String authorization, JsonNode body) {
    }

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String errorType;
    private volatile String responseJson = "{\"MessageId\":\"stub-message-id\"}";
    private SesV2Client client;
    private SesEmailSender sender;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = SesV2Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("stub-access-key-id", "stub-secret-not-a-credential")))
                .overrideConfiguration(c -> c.retryStrategy(b -> b.maxAttempts(1)))
                .build();
        sender = new SesEmailSender(client, FROM);
    }

    @AfterEach
    void stopStub() {
        client.close();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body.isEmpty() ? null : JsonMapper.builder().build().readTree(body)));
        byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (errorType != null) {
            exchange.getResponseHeaders().add("x-amzn-ErrorType", errorType);
        }
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private void refuse(int httpStatus, String type, String message) {
        status = httpStatus;
        errorType = type;
        responseJson = "{\"message\":\"" + message + "\"}";
    }

    @Test
    void sendsOnePlainTextEmailFromTheConfiguredSender() {
        sender.send(TO, "Booking confirmed: BK-1", "Your scooter is ready.\nSee you at 09:00.");

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.path()).isEqualTo("/v2/email/outbound-emails");
            // Signed by the SDK with the region and service of a real SES call.
            assertThat(r.authorization()).startsWith("AWS4-HMAC-SHA256 Credential=stub-access-key-id/")
                    .contains("/ap-south-1/ses/aws4_request");
            JsonNode body = r.body();
            assertThat(body.path("FromEmailAddress").asString()).isEqualTo(FROM);
            assertThat(body.path("Destination").path("ToAddresses")).hasSize(1);
            assertThat(body.path("Destination").path("ToAddresses").get(0).asString()).isEqualTo(TO);
            assertThat(body.path("Destination").has("CcAddresses")).isFalse();
            assertThat(body.path("Destination").has("BccAddresses")).isFalse();
            JsonNode simple = body.path("Content").path("Simple");
            assertThat(simple.path("Subject").path("Data").asString()).isEqualTo("Booking confirmed: BK-1");
            assertThat(simple.path("Subject").path("Charset").asString()).isEqualTo("UTF-8");
            assertThat(simple.path("Body").path("Text").path("Data").asString())
                    .isEqualTo("Your scooter is ready.\nSee you at 09:00.");
            assertThat(simple.path("Body").has("Html")).isFalse();
        });
    }

    @Test
    void aSubjectIsAlwaysOneLine() {
        sender.send(TO, "  Holiday hours\r\nBcc: someone@example.test ", "Body");

        assertThat(requests.getFirst().body().path("Content").path("Simple").path("Subject").path("Data").asString())
                .isEqualTo("Holiday hours Bcc: someone@example.test");
        assertThat(requests.getFirst().body().path("Destination").has("BccAddresses")).isFalse();
    }

    @Test
    void aRefusedMessageIsReportedWithTheErrorCodeButNotTheAddressOrBody() {
        refuse(400, "MessageRejected", "Email address is not verified. The following identities failed the check: "
                + TO);

        assertThatThrownBy(() -> sender.send(TO, "Subject", "Private body"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("SES refused the message: MessageRejected (HTTP 400)")
                .hasMessageNotContaining(TO)
                .hasMessageNotContaining("Private body");
    }

    @Test
    void aThrottledOrFailingServiceIsADeliveryFailure() {
        refuse(429, "TooManyRequestsException", "Rate exceeded");
        assertThatThrownBy(() -> sender.send(TO, "Subject", "Body"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("TooManyRequestsException");

        refuse(500, "InternalFailure", "Something went wrong");
        assertThatThrownBy(() -> sender.send(TO, "Subject", "Body"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void anUnreachableServiceIsADeliveryFailure() {
        server.stop(0);

        assertThatThrownBy(() -> sender.send(TO, "Subject", "Body"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageStartingWith("SES could not be reached: ")
                .hasMessageNotContaining(TO);
    }

    @Test
    void theProductionClientUsesTheConfiguredRegionAndResolvesNoCredentialsUpFront() {
        try (SesV2Client production = SesEmailSender.client("eu-west-1")) {
            assertThat(production.serviceClientConfiguration().region()).isEqualTo(Region.EU_WEST_1);
        }
    }
}
