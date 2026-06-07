package com.angel.aibuilder.openrouter;

import com.angel.aibuilder.ai.AiCompletion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.OptionalLong;

public final class OpenRouterClient {
    private static final Gson GSON = new Gson();
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final URI GENERATION_ENDPOINT = URI.create("https://openrouter.ai/api/v1/generation");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public AiCompletion complete(String apiKey, String model, String effort, String prompt) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.25);
        body.addProperty("reasoning_effort", effort);
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort);
        body.add("reasoning", reasoning);

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

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenRouter returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("OpenRouter response did not include choices.");
        }

        String text = choices.get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
        String generationId = string(json, "id", "");
        Optional<GenerationUsage> generationUsage = pollGenerationUsage(apiKey, generationId, 3, Duration.ofMillis(750));
        if (generationUsage.isPresent() && generationUsage.get().hasCost()) {
            return new AiCompletion(text, generationUsage.get().summary(), "");
        }

        String usageSummary = usageSummaryFromResponse(json, generationId);
        return new AiCompletion(text, usageSummary, generationId);
    }

    public Optional<String> waitForUsageSummary(String apiKey, String generationId, Duration maxWait) throws InterruptedException {
        long deadline = System.nanoTime() + maxWait.toNanos();
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            long delayMillis = attempt == 0 ? 0L : Math.min(5000L, 1000L + (attempt * 500L));
            if (delayMillis > 0L) {
                Thread.sleep(delayMillis);
            }
            Optional<GenerationUsage> usage = pollGenerationUsage(apiKey, generationId, 1, Duration.ZERO);
            if (usage.isPresent() && usage.get().hasCost()) {
                return Optional.of(usage.get().summary());
            }
            attempt++;
        }
        return Optional.empty();
    }

    private Optional<GenerationUsage> pollGenerationUsage(String apiKey, String generationId, int attempts, Duration retryDelay) throws InterruptedException {
        if (generationId.isBlank()) {
            return Optional.empty();
        }

        URI endpoint = URI.create(GENERATION_ENDPOINT + "?id=" + URLEncoder.encode(generationId, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                Thread.sleep(retryDelay.toMillis());
            }
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    continue;
                }
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject data = json.has("data") && json.get("data").isJsonObject()
                        ? json.getAsJsonObject("data")
                        : json;
                Optional<String> cost = costDisplay(data);
                return Optional.of(new GenerationUsage(formatGenerationUsage(data, generationId, cost), cost.isPresent()));
            } catch (IOException | RuntimeException ignored) {
                // The build should still continue if OpenRouter usage metadata is delayed or unavailable.
            }
        }

        return Optional.empty();
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

        Optional<String> cost = generationId.isBlank() ? Optional.empty() : Optional.of("pending");
        return formatUsageLine(generationId, "", input, reasoning, output, cost, "response usage");
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

    private static String formatUsageLine(String generationId, String model, OptionalLong input, OptionalLong reasoning, OptionalLong output, Optional<String> cost, String finishReason) {
        StringBuilder builder = new StringBuilder("OpenRouter usage:");
        builder.append(" input ").append(formatLong(input));
        builder.append(", reasoning ").append(formatLong(reasoning));
        builder.append(", output ").append(formatLong(output));
        builder.append(", cost ").append(cost.orElse("unknown"));
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

    private record GenerationUsage(String summary, boolean hasCost) {
    }
}
