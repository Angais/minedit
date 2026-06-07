package com.angel.aibuilder.codex;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class CodexLocalClient {
    private static final Gson GSON = new Gson();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public String complete(String baseUrl, String model, String effort, String prompt) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("effort", effort);
        body.addProperty("prompt", prompt);

        JsonObject json = sendJson(endpoint(baseUrl, "/complete"), "POST", body, Duration.ofMinutes(12));
        JsonElement text = json.get("text");
        if (text == null || !text.isJsonPrimitive()) {
            throw new IOException("Codex bridge response did not include text.");
        }
        return text.getAsString();
    }

    public Status status(String baseUrl, String currentModel) throws IOException, InterruptedException {
        JsonObject json = sendJson(endpoint(baseUrl, "/status"), "GET", null, Duration.ofSeconds(45));
        boolean needsLogin = bool(json, "needsLogin");
        boolean requiresOpenaiAuth = bool(json, "requiresOpenaiAuth");
        String authLabel = "not required";
        JsonObject account = json.getAsJsonObject("account");
        if (account != null) {
            String type = string(account, "type", "unknown");
            String email = string(account, "email", "");
            String planType = string(account, "planType", "");
            authLabel = type + (email.isEmpty() ? "" : " (" + email + ")") + (planType.isEmpty() ? "" : ", " + planType);
        } else if (requiresOpenaiAuth) {
            authLabel = "not logged in";
        }

        String normalizedModel = normalizeCodexModel(currentModel);
        int modelCount = 0;
        String defaultModel = "";
        boolean currentModelAvailable = false;
        List<String> supportedEfforts = new ArrayList<>();
        JsonArray models = json.getAsJsonArray("models");
        if (models != null) {
            modelCount = models.size();
            for (JsonElement modelElement : models) {
                if (!modelElement.isJsonObject()) {
                    continue;
                }
                JsonObject modelObject = modelElement.getAsJsonObject();
                String id = string(modelObject, "id", "");
                String model = string(modelObject, "model", "");
                if (bool(modelObject, "isDefault")) {
                    defaultModel = model.isEmpty() ? id : model;
                }
                if (normalizedModel.equals(id) || normalizedModel.equals(model)) {
                    currentModelAvailable = true;
                    JsonArray efforts = modelObject.getAsJsonArray("supportedReasoningEfforts");
                    if (efforts != null) {
                        for (JsonElement effort : efforts) {
                            if (effort.isJsonPrimitive()) {
                                supportedEfforts.add(effort.getAsString());
                            }
                        }
                    }
                }
            }
        }

        return new Status(needsLogin, authLabel, modelCount, defaultModel, currentModelAvailable, normalizedModel, supportedEfforts);
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
            throw new IOException("Cannot connect to Codex bridge at " + baseUrl(endpoint) + ". Start it with `npm --prefix bridge start` in the Minedit repo.", e);
        }

        JsonObject json = parseJson(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || !bool(json, "ok")) {
            String message = string(json, "error", "Codex bridge returned HTTP " + response.statusCode());
            throw new IOException(message);
        }
        return json;
    }

    private static JsonObject parseJson(String body) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IOException("Codex bridge returned non-object JSON.");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Codex bridge returned invalid JSON: " + body, e);
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

    private static String normalizeCodexModel(String model) {
        String trimmed = model.trim();
        return trimmed.startsWith("openai/") ? trimmed.substring("openai/".length()) : trimmed;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsString() : fallback;
    }

    public record Status(
            boolean needsLogin,
            String authLabel,
            int modelCount,
            String defaultModel,
            boolean currentModelAvailable,
            String normalizedCurrentModel,
            List<String> supportedEfforts
    ) {
    }
}
