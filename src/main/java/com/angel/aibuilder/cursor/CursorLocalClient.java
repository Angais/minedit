package com.angel.aibuilder.cursor;

import com.angel.aibuilder.ai.AiCompletion;
import com.angel.aibuilder.ai.CancellationToken;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class CursorLocalClient {
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public AiCompletion complete(String baseUrl, String model, String effort, String prompt, CancellationToken token) throws IOException, InterruptedException {
        token.throwIfCancelled();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("effort", effort);
        body.addProperty("prompt", prompt);

        JsonObject json = sendJson(endpoint(baseUrl, "/cursor/complete"), "POST", body, Duration.ofMinutes(12));
        token.throwIfCancelled();
        JsonElement text = json.get("text");
        if (text == null || !text.isJsonPrimitive()) {
            throw new IOException("Cursor bridge response did not include text.");
        }
        return new AiCompletion(text.getAsString(), "");
    }

    public AiCompletion agentBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
        return pollAgentJob(baseUrl, "/cursor/agent-build/start", model, effort, prompt, width, depth, token, progress, null, Duration.ofMinutes(20));
    }

    public AiCompletion agentStepByStepBuild(String baseUrl, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress, Consumer<StepBatch> batchConsumer) throws IOException, InterruptedException {
        return pollAgentJob(baseUrl, "/cursor/agent-build/step-by-step/start", model, effort, prompt, width, depth, token, progress, batchConsumer, Duration.ofMinutes(30));
    }

    public Status status(String baseUrl) throws IOException, InterruptedException {
        JsonObject json = sendJson(endpoint(baseUrl, "/cursor/status"), "GET", null, Duration.ofSeconds(20));
        return new Status(
                bool(json, "authenticated"),
                string(json, "status", ""),
                string(json, "binary", "agent")
        );
    }

    public List<Model> listModels(String baseUrl) throws IOException, InterruptedException {
        JsonObject json = sendJson(endpoint(baseUrl, "/cursor/models"), "GET", null, Duration.ofSeconds(45));
        JsonArray models = json.getAsJsonArray("models");
        List<Model> result = new ArrayList<>();
        if (models != null) {
            for (JsonElement element : models) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String id = string(object, "id", "");
                if (!id.isBlank()) {
                    result.add(new Model(id, string(object, "displayName", id)));
                }
            }
        }
        return List.copyOf(result);
    }

    private AiCompletion pollAgentJob(String baseUrl, String startPath, String model, String effort, String prompt, int width, int depth, CancellationToken token, Consumer<String> progress, Consumer<StepBatch> batchConsumer, Duration maxWait) throws IOException, InterruptedException {
        token.throwIfCancelled();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("effort", effort);
        body.addProperty("prompt", prompt);
        body.addProperty("width", width);
        body.addProperty("depth", depth);

        JsonObject started = sendJson(endpoint(baseUrl, startPath), "POST", body, Duration.ofSeconds(45));
        String jobId = string(started, "jobId", "");
        if (jobId.isBlank()) {
            throw new IOException("Cursor bridge did not return an agent job id.");
        }

        Closeable cancelJob = () -> cancelAgentJobAsync(baseUrl, jobId);
        token.register(cancelJob);
        long deadline = System.nanoTime() + maxWait.toNanos();
        int afterSeq = 0;
        int afterBatch = 0;
        StringBuilder combinedCode = new StringBuilder();
        try {
            while (System.nanoTime() < deadline) {
                token.throwIfCancelled();
                JsonObject status = sendJson(endpoint(baseUrl, "/cursor/agent-build/status?id=" + encode(jobId) + "&after=" + afterSeq + "&afterBatch=" + afterBatch), "GET", null, Duration.ofSeconds(30));
                JsonArray events = status.getAsJsonArray("events");
                if (events != null) {
                    for (JsonElement eventElement : events) {
                        if (!eventElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject event = eventElement.getAsJsonObject();
                        afterSeq = Math.max(afterSeq, integer(event, "seq", afterSeq));
                        String message = string(event, "message", "");
                        if (!message.isBlank()) {
                            progress.accept(message);
                        }
                    }
                }

                JsonArray batches = status.getAsJsonArray("batches");
                if (batchConsumer != null && batches != null) {
                    for (JsonElement batchElement : batches) {
                        if (!batchElement.isJsonObject()) {
                            continue;
                        }
                        token.throwIfCancelled();
                        JsonObject batchObject = batchElement.getAsJsonObject();
                        int batchId = integer(batchObject, "id", afterBatch);
                        afterBatch = Math.max(afterBatch, batchId);
                        String code = string(batchObject, "code", "");
                        if (code.isBlank()) {
                            continue;
                        }
                        StepBatch batch = new StepBatch(
                                batchId,
                                code,
                                integer(batchObject, "operationCount", 0),
                                integer(batchObject, "nonAirBlocks", 0),
                                string(batchObject, "summary", "")
                        );
                        if (!combinedCode.isEmpty()) {
                            combinedCode.append("\n\n");
                        }
                        combinedCode.append("// step ").append(batch.id()).append("\n").append(batch.code());
                        batchConsumer.accept(batch);
                    }
                }

                String state = string(status, "status", "running");
                if ("completed".equals(state)) {
                    String text = string(status, "text", combinedCode.toString());
                    if (text.isBlank()) {
                        text = combinedCode.toString();
                    }
                    String summary = string(status, "summary", "");
                    return new AiCompletion(text, summary);
                }
                if ("cancelled".equals(state)) {
                    throw new InterruptedException("Cursor agent build was stopped.");
                }
                if ("failed".equals(state)) {
                    throw new IOException(string(status, "error", "Cursor agent build failed."));
                }

                Thread.sleep(batchConsumer == null ? 2000 : 1000);
            }
        } finally {
            token.unregister(cancelJob);
        }

        throw new IOException("Cursor agent build timed out after " + maxWait.toMinutes() + " minutes.");
    }

    private JsonObject sendJson(URI endpoint, String method, JsonObject body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new IOException("Cannot connect to local Minedit bridge at " + baseUrl(endpoint) + ". Start it with `npm --prefix bridge start` in the Minedit repo.", e);
        }

        JsonObject json = parseJson(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || !bool(json, "ok")) {
            String message = string(json, "error", "Cursor bridge returned HTTP " + response.statusCode());
            throw new IOException(message);
        }
        return json;
    }

    private void cancelAgentJobAsync(String baseUrl, String jobId) {
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, "/cursor/agent-build/cancel"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ignored -> null);
    }

    private static JsonObject parseJson(String body) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IOException("Cursor bridge returned non-object JSON.");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Cursor bridge returned invalid JSON: " + body, e);
        }
    }

    private static URI endpoint(String baseUrl, String path) {
        String normalized = baseUrl.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized + path);
    }

    private static String baseUrl(URI endpoint) {
        int port = endpoint.getPort();
        return endpoint.getScheme() + "://" + endpoint.getHost() + (port >= 0 ? ":" + port : "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsInt() : fallback;
    }

    public record Status(boolean authenticated, String status, String binary) {
    }

    public record Model(String id, String displayName) {
    }

    public record StepBatch(int id, String code, int operationCount, int nonAirBlocks, String summary) {
    }
}
