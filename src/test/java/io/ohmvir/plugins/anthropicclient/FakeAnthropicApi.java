package io.ohmvir.plugins.anthropicclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/** A minimal in-process stand-in for the Anthropic HTTP API that records requests and serves canned responses. */
final class FakeAnthropicApi implements AutoCloseable {
    record RecordedRequest(String method, String path, String query, Map<String, String> headers, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private record CannedResponse(int status, String body) {}

    private final HttpServer server;
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Deque<CannedResponse> messageResponses = new ConcurrentLinkedDeque<>();
    private final Map<String, String> modelInfos = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> modelListPages = Collections.synchronizedMap(new HashMap<>());

    FakeAnthropicApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void enqueueMessage(String json) {
        messageResponses.add(new CannedResponse(200, json));
    }

    void enqueueError(int status, String errorType, String message) {
        messageResponses.add(new CannedResponse(
                status,
                "{\"type\":\"error\",\"error\":{\"type\":\"" + errorType + "\",\"message\":\"" + message + "\"}}"));
    }

    void setModelInfo(String modelId, String json) {
        modelInfos.put(modelId, json);
    }

    /** Serves a page of the model list; {@code afterId} is null for the first page. */
    void setModelListPage(String afterId, String json) {
        modelListPages.put(afterId == null ? "" : afterId, json);
    }

    List<RecordedRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    List<RecordedRequest> messageRequests() {
        return requests().stream().filter(r -> r.path().equals("/v1/messages")).toList();
    }

    List<RecordedRequest> modelRequests() {
        return requests().stream()
                .filter(r -> r.path().startsWith("/v1/models"))
                .toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        requests.add(new RecordedRequest(exchange.getRequestMethod(), path, query, headers, body));

        CannedResponse response;
        if (path.equals("/v1/messages")) {
            response = messageResponses.poll();
            if (response == null) {
                response = new CannedResponse(
                        400,
                        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"unexpected call\"}}");
            }
        } else if (path.equals("/v1/models")) {
            String afterId = "";
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("after_id=")) {
                        afterId = param.substring("after_id=".length());
                    }
                }
            }
            String page = modelListPages.get(afterId);
            response = page != null ? new CannedResponse(200, page) : notFound();
        } else if (path.startsWith("/v1/models/")) {
            String info = modelInfos.get(path.substring("/v1/models/".length()));
            response = info != null ? new CannedResponse(200, info) : notFound();
        } else {
            response = notFound();
        }

        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static CannedResponse notFound() {
        return new CannedResponse(
                404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"not found\"}}");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- Response builders ----

    static String message(String stopReason, String... contentBlocks) {
        return message(stopReason, 10, 5, 0, 0, contentBlocks);
    }

    static String message(
            String stopReason,
            long inputTokens,
            long outputTokens,
            long cacheRead,
            long cacheCreation,
            String... contentBlocks) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
                + "\"content\":[" + String.join(",", contentBlocks) + "],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":" + outputTokens
                + ",\"cache_read_input_tokens\":" + cacheRead + ",\"cache_creation_input_tokens\":" + cacheCreation
                + "}}";
    }

    static String text(String text) {
        return "{\"type\":\"text\",\"text\":" + quote(text) + ",\"citations\":null}";
    }

    static String toolUse(String id, String name, String inputJson) {
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    static String thinking(String thinking, String signature) {
        return "{\"type\":\"thinking\",\"thinking\":" + quote(thinking) + ",\"signature\":\"" + signature + "\"}";
    }

    static String redactedThinking(String data) {
        return "{\"type\":\"redacted_thinking\",\"data\":\"" + data + "\"}";
    }

    /**
     * Model info as returned by the Models API. {@code adaptive} also controls whether effort is supported, and
     * {@code xhigh} whether the xhigh effort level is. A null {@code maxInputTokens} omits the field.
     */
    static String modelInfo(
            String id,
            Long maxInputTokens,
            long maxTokens,
            boolean adaptive,
            boolean budget,
            boolean thinking,
            boolean xhigh) {
        String supported = "{\"supported\":true}";
        String unsupported = "{\"supported\":false}";
        return "{\"type\":\"model\",\"id\":\"" + id + "\",\"display_name\":\"Test " + id + "\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\","
                + (maxInputTokens != null ? "\"max_input_tokens\":" + maxInputTokens + "," : "")
                + "\"max_tokens\":" + maxTokens + ","
                + "\"capabilities\":{"
                + "\"batch\":" + supported + ","
                + "\"citations\":" + supported + ","
                + "\"code_execution\":" + unsupported + ","
                + "\"context_management\":{\"supported\":false},"
                + "\"effort\":{\"supported\":" + adaptive + ",\"low\":" + supported + ",\"medium\":" + supported
                + ",\"high\":" + supported + ",\"xhigh\":" + (xhigh ? supported : unsupported) + ",\"max\":"
                + supported + "},"
                + "\"image_input\":" + supported + ","
                + "\"pdf_input\":" + supported + ","
                + "\"structured_outputs\":" + unsupported + ","
                + "\"thinking\":{\"supported\":" + thinking + ",\"types\":{\"adaptive\":{\"supported\":" + adaptive
                + "},\"enabled\":{\"supported\":" + budget + "}}}"
                + "}}";
    }

    static String quote(String s) {
        return new com.google.gson.JsonPrimitive(s).toString();
    }
}
