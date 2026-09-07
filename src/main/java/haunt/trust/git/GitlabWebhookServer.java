package haunt.trust.git;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class GitlabWebhookServer {
    private static final int MAX_BODY_SIZE = 1024 * 1024;
    private static final long MAX_TIMESTAMP_DRIFT_SECONDS = 300;
    private static final int REMEMBERED_DELIVERIES = 1_000;

    private final HttpServer server;
    private final String secret;
    private final String signingToken;
    private final GitlabWebhookHandler handler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> deliveries = new LinkedHashSet<>();

    GitlabWebhookServer(
            int port,
            String path,
            String secret,
            String signingToken,
            GitlabWebhookHandler handler
    ) throws IOException {
        this.secret = secret;
        this.signingToken = signingToken;
        this.handler = handler;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext(path, this::handle);
    }

    void start() {
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }

            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_SIZE + 1);
            if (body.length > MAX_BODY_SIZE) {
                respond(exchange, 413, "payload too large");
                return;
            }

            String json = new String(body, StandardCharsets.UTF_8);
            if (!authenticated(exchange, json)) {
                respond(exchange, 401, "unauthorized");
                return;
            }

            String deliveryId = header(exchange, "webhook-id");
            if (deliveryId.isBlank()) {
                deliveryId = header(exchange, "Idempotency-Key");
            }
            if (!deliveryId.isBlank() && !remember(deliveryId)) {
                respond(exchange, 200, "duplicate");
                return;
            }

            String event = header(exchange, "X-Gitlab-Event");
            respond(exchange, 200, "ok");
            worker.execute(() -> {
                try {
                    handler.handle(event, json);
                } catch (Exception exception) {
                    System.err.println("GitLab webhook processing error: " + exception.getMessage());
                }
            });
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, exception.getMessage());
        } catch (Exception exception) {
            System.err.println("GitLab webhook error: " + exception.getMessage());
            respond(exchange, 500, "internal error");
        } finally {
            exchange.close();
        }
    }

    private boolean authenticated(HttpExchange exchange, String body) {
        String signature = header(exchange, "webhook-signature");
        if (!signature.isBlank() && !signingToken.isBlank()) {
            return validSignature(
                    header(exchange, "webhook-id"),
                    header(exchange, "webhook-timestamp"),
                    signature,
                    body
            );
        }
        return validToken(header(exchange, "X-Gitlab-Token"));
    }

    private boolean validToken(String token) {
        return !secret.isBlank() && !token.isBlank() && MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean validSignature(String id, String timestamp, String signatures, String body) {
        try {
            long sentAt = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - sentAt) > MAX_TIMESTAMP_DRIFT_SECONDS) {
                return false;
            }

            String token = signingToken.startsWith("whsec_") ? signingToken.substring(6) : signingToken;
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(Base64.getDecoder().decode(token), "HmacSHA256"));
            String expected = "v1," + Base64.getEncoder().encodeToString(
                    hmac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8))
            );

            for (String signature : signatures.split(" ")) {
                if (MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        signature.getBytes(StandardCharsets.UTF_8)
                )) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private synchronized boolean remember(String deliveryId) {
        if (!deliveries.add(deliveryId)) {
            return false;
        }
        if (deliveries.size() > REMEMBERED_DELIVERIES) {
            deliveries.remove(deliveries.iterator().next());
        }
        return true;
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
