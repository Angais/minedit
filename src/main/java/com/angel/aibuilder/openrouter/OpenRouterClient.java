package com.angel.aibuilder.openrouter;

import com.angel.aibuilder.ai.AiCompletion;
import com.angel.aibuilder.ai.CancellationToken;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

public final class OpenRouterClient {
    private static final Gson GSON = new Gson();
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final URI GENERATION_ENDPOINT = URI.create("https://openrouter.ai/api/v1/generation");
    private static final String MODEL_ENDPOINTS_BASE = "https://openrouter.ai/api/v1/models/";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public AiCompletion complete(String apiKey, String model, String effort, String prompt) throws IOException, InterruptedException {
        return complete(apiKey, model, effort, prompt, true, new CancellationToken(), ignored -> {
        });
    }

    public AiCompletion complete(String apiKey, String model, String effort, String prompt, Consumer<String> progress) throws IOException, InterruptedException {
        return complete(apiKey, model, effort, prompt, true, new CancellationToken(), progress);
    }

    public AiCompletion complete(String apiKey, String model, String effort, String prompt, boolean stream, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
        return complete(apiKey, model, effort, prompt, "", stream, token, progress);
    }

    public AiCompletion complete(String apiKey, String model, String effort, String prompt, String providerSlug, boolean stream, CancellationToken token, Consumer<String> progress) throws IOException, InterruptedException {
        token.throwIfCancelled();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.25);
        body.addProperty("reasoning_effort", effort);
        body.addProperty("stream", stream);
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort);
        body.add("reasoning", reasoning);
        if (providerSlug != null && !providerSlug.isBlank()) {
            JsonArray only = new JsonArray();
            only.add(providerSlug);
            JsonObject provider = new JsonObject();
            provider.add("only", only);
            provider.addProperty("allow_fallbacks", false);
            body.add("provider", provider);
        }

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://localhost/minedit")
                .header("X-Title", "Minedit NeoForge Mod")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        if (!stream) {
            progress.accept("OpenRouter: waiting for non-stream response...");
            return completeNonStreaming(apiKey, request, token);
        }

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        token.throwIfCancelled();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenRouter returned HTTP " + response.statusCode() + ": " + readBody(response.body()));
        }

        progress.accept("OpenRouter: stream connected.");
        String headerGenerationId = response.headers().firstValue("X-Generation-Id").orElse("");
        InputStream bodyStream = response.body();
        token.register(bodyStream);
        StreamingResult streaming;
        try {
            streaming = readStreamingResponse(bodyStream, progress, headerGenerationId, token);
        } catch (IOException e) {
            if (token.isCancelled()) {
                throw new InterruptedException("Minedit job was stopped.");
            }
            throw e;
        } finally {
            token.unregister(bodyStream);
        }
        String text = streaming.text();
        if (text.isBlank()) {
            throw new IOException("OpenRouter streamed no assistant content.");
        }

        String generationId = streaming.generationId();
        token.throwIfCancelled();
        Optional<GenerationUsage> generationUsage = pollGenerationUsage(apiKey, generationId, 3, Duration.ofMillis(750));
        if (generationUsage.isPresent() && generationUsage.get().hasCost()) {
            return new AiCompletion(text, generationUsage.get().summary(), "");
        }

        String usageSummary = streaming.usage() == null
                ? (generationId.isBlank() ? "" : "OpenRouter usage: unavailable for " + generationId + ".")
                : usageSummaryFromResponse(streaming.usageWrapper(), generationId);
        return new AiCompletion(text, usageSummary, generationId);
    }

    public List<ProviderEndpoint> listProvidersForModel(String apiKey, String model) throws IOException, InterruptedException {
        int slash = model.indexOf('/');
        if (slash <= 0 || slash == model.length() - 1) {
            throw new IOException("OpenRouter model id must include its author, for example openai/gpt-5.5.");
        }

        String author = encodePathSegment(model.substring(0, slash));
        String slug = encodePathSegment(model.substring(slash + 1));
        URI endpoint = URI.create(MODEL_ENDPOINTS_BASE + author + "/" + slug + "/endpoints");
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenRouter endpoints lookup returned HTTP " + response.statusCode() + ".");
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("OpenRouter endpoints lookup returned invalid JSON.", e);
        }

        JsonObject data = json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : null;
        JsonArray endpoints = data != null && data.has("endpoints") && data.get("endpoints").isJsonArray()
                ? data.getAsJsonArray("endpoints")
                : new JsonArray();
        Map<String, ProviderEndpoint> providers = new LinkedHashMap<>();
        for (JsonElement element : endpoints) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject provider = element.getAsJsonObject();
            String tag = string(provider, "tag", "").trim();
            if (tag.isBlank()) {
                continue;
            }
            providers.putIfAbsent(tag, new ProviderEndpoint(tag, string(provider, "provider_name", tag)));
        }
        return providers.values().stream()
                .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                .toList();
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private AiCompletion completeNonStreaming(String apiKey, HttpRequest request, CancellationToken token) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        token.throwIfCancelled();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenRouter returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("OpenRouter returned invalid JSON.", e);
        }
        if (json.has("error") && json.get("error").isJsonObject()) {
            JsonObject error = json.getAsJsonObject("error");
            throw new IOException("OpenRouter error: " + string(error, "message", error.toString()));
        }

        String text = assistantText(json);
        if (text.isBlank()) {
            throw new IOException("OpenRouter returned no assistant content.");
        }

        String generationId = string(json, "id", "");
        token.throwIfCancelled();
        Optional<GenerationUsage> generationUsage = pollGenerationUsage(apiKey, generationId, 3, Duration.ofMillis(750));
        if (generationUsage.isPresent() && generationUsage.get().hasCost()) {
            return new AiCompletion(text, generationUsage.get().summary(), "");
        }

        JsonObject usage = json.has("usage") && json.get("usage").isJsonObject() ? json.getAsJsonObject("usage") : null;
        String usageSummary = usage == null
                ? (generationId.isBlank() ? "" : "OpenRouter usage: unavailable for " + generationId + ".")
                : usageSummaryFromResponse(wrapUsage(usage), generationId);
        return new AiCompletion(text, usageSummary, generationId);
    }

    public Optional<String> waitForCostSummary(String apiKey, String generationId, Duration maxWait) throws InterruptedException {
        long deadline = System.nanoTime() + maxWait.toNanos();
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            long delayMillis = attempt == 0 ? 0L : Math.min(5000L, 1000L + (attempt * 500L));
            if (delayMillis > 0L) {
                Thread.sleep(delayMillis);
            }
            try {
                UsageReport usage = fetchUsageReport(apiKey, generationId);
                if (usage.hasCost()) {
                    return Optional.of(usage.costSummary());
                }
            } catch (IOException | RuntimeException ignored) {
                // The automatic follow-up should not fail the build or spam transient lookup errors.
            }
            attempt++;
        }
        return Optional.empty();
    }

    public UsageReport fetchUsageReport(String apiKey, String generationId) throws IOException, InterruptedException {
        if (generationId.isBlank()) {
            throw new IOException("Generation id is empty.");
        }

        URI endpoint = URI.create(GENERATION_ENDPOINT + "?id=" + URLEncoder.encode(generationId, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenRouter generation lookup returned HTTP " + response.statusCode() + ".");
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("OpenRouter generation lookup returned invalid JSON.", e);
        }

        JsonObject data = json.has("data") && json.get("data").isJsonObject()
                ? json.getAsJsonObject("data")
                : json;
        Optional<String> cost = costDisplay(data);
        return new UsageReport(formatGenerationUsage(data, generationId, cost), formatGenerationCost(data, generationId, cost), cost.isPresent());
    }

    private Optional<GenerationUsage> pollGenerationUsage(String apiKey, String generationId, int attempts, Duration retryDelay) throws InterruptedException {
        if (generationId.isBlank()) {
            return Optional.empty();
        }

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                Thread.sleep(retryDelay.toMillis());
            }
            try {
                UsageReport report = fetchUsageReport(apiKey, generationId);
                return Optional.of(new GenerationUsage(report.summary(), report.hasCost()));
            } catch (IOException | RuntimeException ignored) {
                // The build should still continue if OpenRouter usage metadata is delayed or unavailable.
            }
        }

        return Optional.empty();
    }

    private static StreamingResult readStreamingResponse(InputStream body, Consumer<String> progress, String initialGenerationId, CancellationToken token) throws IOException, InterruptedException {
        StringBuilder content = new StringBuilder();
        StringBuilder eventData = new StringBuilder();
        StreamProgress streamProgress = new StreamProgress(progress);
        String generationId = initialGenerationId;
        JsonObject usage = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                token.throwIfCancelled();
                if (line.isBlank()) {
                    StreamChunk chunk = processEventData(eventData, content, generationId, usage, streamProgress);
                    generationId = chunk.generationId();
                    usage = chunk.usage();
                    eventData.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!eventData.isEmpty()) {
                        eventData.append('\n');
                    }
                    eventData.append(line.substring(5).trim());
                }
            }
        }

        token.throwIfCancelled();
        StreamChunk chunk = processEventData(eventData, content, generationId, usage, streamProgress);
        generationId = chunk.generationId();
        usage = chunk.usage();
        streamProgress.finish(content.length());
        return new StreamingResult(content.toString(), generationId, usage);
    }

    private static StreamChunk processEventData(StringBuilder eventData, StringBuilder content, String generationId, JsonObject usage, StreamProgress progress) throws IOException {
        if (eventData.isEmpty()) {
            return new StreamChunk(generationId, usage);
        }
        String data = eventData.toString().trim();
        if (data.isBlank() || "[DONE]".equals(data)) {
            return new StreamChunk(generationId, usage);
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(data).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("OpenRouter stream returned invalid JSON chunk: " + data, e);
        }

        if (json.has("error") && json.get("error").isJsonObject()) {
            JsonObject error = json.getAsJsonObject("error");
            throw new IOException("OpenRouter stream error: " + string(error, "message", error.toString()));
        }

        String nextGenerationId = string(json, "id", generationId);
        JsonObject nextUsage = json.has("usage") && json.get("usage").isJsonObject() ? json.getAsJsonObject("usage") : usage;
        handleResponsesApiEvent(json, content, progress);

        JsonArray choices = json.getAsJsonArray("choices");
        if (choices != null) {
            for (JsonElement choiceElement : choices) {
                if (!choiceElement.isJsonObject()) {
                    continue;
                }
                JsonObject choice = choiceElement.getAsJsonObject();
                JsonObject delta = choice.getAsJsonObject("delta");
                if (delta == null) {
                    continue;
                }
                JsonElement contentElement = delta.get("content");
                if (contentElement != null && contentElement.isJsonPrimitive()) {
                    String text = contentElement.getAsString();
                    if (!text.isEmpty()) {
                        content.append(text);
                        progress.output(content.length());
                    }
                }
                JsonArray details = delta.getAsJsonArray("reasoning_details");
                if (details != null) {
                    handleReasoningDetails(details, progress);
                } else if (delta.has("reasoning")) {
                    JsonElement reasoning = delta.get("reasoning");
                    if (reasoning != null && reasoning.isJsonPrimitive()) {
                        progress.hiddenReasoning(reasoning.getAsString().length());
                    }
                }
            }
        }

        return new StreamChunk(nextGenerationId, nextUsage);
    }

    private static void handleResponsesApiEvent(JsonObject json, StringBuilder content, StreamProgress progress) {
        String type = string(json, "type", "");
        if ("response.output_text.delta".equals(type)) {
            String delta = string(json, "delta", "");
            if (!delta.isBlank()) {
                content.append(delta);
                progress.output(content.length());
            }
        } else if ("response.reasoning.delta".equals(type)) {
            String delta = string(json, "delta", "");
            progress.hiddenReasoning(delta.length());
        } else if ("response.reasoning_summary.delta".equals(type) || "response.reasoning.summary.delta".equals(type)) {
            progress.reasoningSummary(string(json, "delta", ""));
        }
    }

    private static void handleReasoningDetails(JsonArray details, StreamProgress progress) {
        for (JsonElement detailElement : details) {
            if (!detailElement.isJsonObject()) {
                continue;
            }
            JsonObject detail = detailElement.getAsJsonObject();
            String type = string(detail, "type", "");
            String summary = reasoningSummary(detail, type);
            if (!summary.isBlank()) {
                progress.reasoningSummary(summary);
                continue;
            }
            if ("reasoning.text".equals(type) || detail.has("text")) {
                progress.hiddenReasoning(string(detail, "text", "").length());
            }
        }
    }

    private static String reasoningSummary(JsonObject detail, String type) {
        if (!type.contains("summary") && !detail.has("summary")) {
            return "";
        }
        JsonElement summary = detail.get("summary");
        if (summary == null || summary.isJsonNull()) {
            return "";
        }
        if (summary.isJsonPrimitive()) {
            return summary.getAsString();
        }
        if (summary.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement element : summary.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    if (!builder.isEmpty()) {
                        builder.append(' ');
                    }
                    builder.append(element.getAsString());
                }
            }
            return builder.toString();
        }
        return "";
    }

    private static String readBody(InputStream body) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String assistantText(JsonObject json) {
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices != null) {
            for (JsonElement choiceElement : choices) {
                if (!choiceElement.isJsonObject()) {
                    continue;
                }
                JsonObject choice = choiceElement.getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                if (message == null) {
                    continue;
                }
                String text = contentText(message.get("content"));
                if (!text.isBlank()) {
                    return text;
                }
            }
        }

        JsonArray output = json.getAsJsonArray("output");
        if (output != null) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement itemElement : output) {
                if (!itemElement.isJsonObject()) {
                    continue;
                }
                JsonObject item = itemElement.getAsJsonObject();
                JsonArray content = item.getAsJsonArray("content");
                if (content == null) {
                    continue;
                }
                for (JsonElement contentElement : content) {
                    if (!contentElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject part = contentElement.getAsJsonObject();
                    String text = string(part, "text", "");
                    if (!text.isBlank()) {
                        builder.append(text);
                    }
                }
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }

        return string(json, "text", "");
    }

    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (JsonElement partElement : content.getAsJsonArray()) {
            if (partElement.isJsonPrimitive()) {
                builder.append(partElement.getAsString());
            } else if (partElement.isJsonObject()) {
                JsonObject part = partElement.getAsJsonObject();
                builder.append(string(part, "text", ""));
            }
        }
        return builder.toString();
    }

    private static JsonObject wrapUsage(JsonObject usage) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("usage", usage);
        return wrapper;
    }

    private static String usageSummaryFromResponse(JsonObject json, String generationId) {
        JsonObject usage = json.getAsJsonObject("usage");
        if (usage == null) {
            return generationId.isBlank() ? "" : "OpenRouter usage: unavailable for " + generationId + ".";
        }

        OptionalLong input = longValue(usage, "prompt_tokens");
        OptionalLong completion = longValue(usage, "completion_tokens");
        OptionalLong reasoning = nestedLongValue(usage, "completion_tokens_details", "reasoning_tokens");
        OptionalLong output = subtractIfPresent(completion, reasoning);

        return formatUsageLine(generationId, "", input, reasoning, output, Optional.empty(), "response usage");
    }

    private static String formatGenerationUsage(JsonObject data, String fallbackGenerationId, Optional<String> cost) {
        String generationId = string(data, "generation_id", string(data, "id", fallbackGenerationId));
        String model = string(data, "model", "");
        String finishReason = string(data, "finish_reason", "");

        OptionalLong input = firstLong(data, "native_tokens_prompt", "tokens_prompt");
        OptionalLong reasoning = longValue(data, "native_tokens_reasoning");
        OptionalLong nativeCompletion = longValue(data, "native_tokens_completion");
        OptionalLong regularCompletion = longValue(data, "tokens_completion");
        OptionalLong output = subtractIfPresent(nativeCompletion, reasoning);
        if (output.isEmpty()) {
            output = regularCompletion;
        }

        return formatUsageLine(generationId, model, input, reasoning, output, cost, finishReason);
    }

    private static String formatGenerationCost(JsonObject data, String fallbackGenerationId, Optional<String> cost) {
        String generationId = string(data, "generation_id", string(data, "id", fallbackGenerationId));
        String model = string(data, "model", "");
        String finishReason = string(data, "finish_reason", "");

        StringBuilder builder = new StringBuilder("OpenRouter cost:");
        builder.append(" ").append(cost.orElse("unknown"));
        if (!model.isBlank()) {
            builder.append(", model ").append(model);
        }
        if (!finishReason.isBlank()) {
            builder.append(", finish ").append(finishReason);
        }
        if (!generationId.isBlank()) {
            builder.append(", id ").append(generationId);
        }
        builder.append(".");
        return builder.toString();
    }

    private static String formatUsageLine(String generationId, String model, OptionalLong input, OptionalLong reasoning, OptionalLong output, Optional<String> cost, String finishReason) {
        StringBuilder builder = new StringBuilder("OpenRouter usage:");
        builder.append(" input ").append(formatLong(input));
        builder.append(", reasoning ").append(formatLong(reasoning));
        builder.append(", output ").append(formatLong(output));
        cost.ifPresent(value -> builder.append(", cost ").append(value));
        if (!model.isBlank()) {
            builder.append(", model ").append(model);
        }
        if (!finishReason.isBlank()) {
            builder.append(", finish ").append(finishReason);
        }
        if (!generationId.isBlank()) {
            builder.append(", id ").append(generationId);
        }
        builder.append(".");
        return builder.toString();
    }

    private static String formatLong(OptionalLong value) {
        return value.isPresent() ? String.valueOf(value.getAsLong()) : "unknown";
    }

    private static OptionalLong subtractIfPresent(OptionalLong total, OptionalLong subtract) {
        if (total.isEmpty()) {
            return OptionalLong.empty();
        }
        if (subtract.isEmpty()) {
            return total;
        }
        return OptionalLong.of(Math.max(0, total.getAsLong() - subtract.getAsLong()));
    }

    private static OptionalLong firstLong(JsonObject object, String... keys) {
        for (String key : keys) {
            OptionalLong value = longValue(object, key);
            if (value.isPresent()) {
                return value;
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong nestedLongValue(JsonObject object, String parent, String child) {
        JsonObject nested = object.getAsJsonObject(parent);
        return nested == null ? OptionalLong.empty() : longValue(nested, child);
    }

    private static OptionalLong longValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Math.round(value.getAsDouble()));
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return OptionalLong.empty();
        }
    }

    private static Optional<String> costDisplay(JsonObject object) {
        Optional<BigDecimal> byokUsage = numberValue(object, "byok_usage_inference");
        if (byokUsage.isPresent()) {
            return Optional.of(formatDollars(byokUsage.get()) + " BYOK");
        }
        if (bool(object, "is_byok")) {
            Optional<BigDecimal> upstreamInference = numberValue(object, "upstream_inference_cost");
            if (upstreamInference.isPresent() && upstreamInference.get().signum() != 0) {
                return Optional.of(formatDollars(upstreamInference.get()) + " upstream");
            }
            Optional<BigDecimal> upstreamUsage = numberValue(object, "usage_upstream");
            if (upstreamUsage.isPresent() && upstreamUsage.get().signum() != 0) {
                return Optional.of(formatDollars(upstreamUsage.get()) + " upstream");
            }
            return Optional.empty();
        }

        Optional<BigDecimal> totalCost = numberValue(object, "total_cost");
        if (totalCost.isPresent()) {
            return Optional.of(formatDollars(totalCost.get()));
        }
        Optional<BigDecimal> usage = numberValue(object, "usage");
        if (usage.isPresent()) {
            return Optional.of(formatDollars(usage.get()) + " OpenRouter");
        }
        Optional<BigDecimal> upstreamUsage = numberValue(object, "usage_upstream");
        if (upstreamUsage.isPresent()) {
            return Optional.of(formatDollars(upstreamUsage.get()) + " upstream");
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> numberValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return Optional.empty();
        }
        try {
            return Optional.of(value.getAsBigDecimal());
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    private static String formatDollars(BigDecimal value) {
        return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() && value.getAsBoolean();
    }

    private static final class StreamProgress {
        private static final long MESSAGE_INTERVAL_NANOS = Duration.ofSeconds(4).toNanos();
        private static final int OUTPUT_CHAR_INTERVAL = 2400;
        private static final int REASONING_CHAR_INTERVAL = 2500;

        private final Consumer<String> progress;
        private final StringBuilder summaryBuffer = new StringBuilder();
        private long lastMessage = System.nanoTime();
        private int lastOutputChars = 0;
        private int hiddenReasoningChars = 0;
        private int lastReasoningChars = 0;
        private boolean announcedHiddenReasoning = false;

        private StreamProgress(Consumer<String> progress) {
            this.progress = progress;
        }

        private void reasoningSummary(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            summaryBuffer.append(text.replaceAll("\\s+", " "));
            if (summaryBuffer.length() >= 180 || summaryBuffer.toString().matches(".*[.!?]\\s*$")) {
                send("OpenRouter reasoning summary: " + trim(summaryBuffer.toString(), 220));
                summaryBuffer.setLength(0);
            }
        }

        private void hiddenReasoning(int charCount) {
            hiddenReasoningChars += Math.max(0, charCount);
            long now = System.nanoTime();
            if (!announcedHiddenReasoning) {
                announcedHiddenReasoning = true;
                lastMessage = now;
                progress.accept("OpenRouter: model is reasoning. Raw hidden chain-of-thought is not shown; summaries are shown when the provider sends them.");
                return;
            }
            if (hiddenReasoningChars - lastReasoningChars >= REASONING_CHAR_INTERVAL && now - lastMessage >= MESSAGE_INTERVAL_NANOS) {
                lastReasoningChars = hiddenReasoningChars;
                lastMessage = now;
                progress.accept("OpenRouter: still reasoning...");
            }
        }

        private void output(int outputChars) {
            long now = System.nanoTime();
            if (outputChars - lastOutputChars >= OUTPUT_CHAR_INTERVAL && now - lastMessage >= MESSAGE_INTERVAL_NANOS) {
                lastOutputChars = outputChars;
                lastMessage = now;
                progress.accept("OpenRouter: receiving build code (" + outputChars + " chars)...");
            }
        }

        private void finish(int outputChars) {
            if (!summaryBuffer.isEmpty()) {
                send("OpenRouter reasoning summary: " + trim(summaryBuffer.toString(), 220));
                summaryBuffer.setLength(0);
            }
            progress.accept("OpenRouter: stream complete, received " + outputChars + " chars.");
        }

        private void send(String message) {
            lastMessage = System.nanoTime();
            progress.accept(message);
        }

        private static String trim(String text, int maxChars) {
            String compact = text.replaceAll("\\s+", " ").trim();
            return compact.length() <= maxChars ? compact : compact.substring(0, maxChars - 3) + "...";
        }
    }

    private record GenerationUsage(String summary, boolean hasCost) {
    }

    private record StreamingResult(String text, String generationId, JsonObject usage) {
        private JsonObject usageWrapper() {
            JsonObject wrapper = new JsonObject();
            wrapper.add("usage", usage);
            return wrapper;
        }
    }

    private record StreamChunk(String generationId, JsonObject usage) {
    }

    public record ProviderEndpoint(String slug, String name) {
    }

    public record UsageReport(String summary, String costSummary, boolean hasCost) {
    }
}
