package dev.backendagent.config;

import java.net.URI;
import java.time.Duration;

/** Configuration values only; do not add a toString that prints the API key. */
public final class ModelConfig {
    private final String apiKey;
    private final URI baseUrl;
    private final String modelName;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    public ModelConfig(String apiKey, URI baseUrl, String modelName,
                       Duration connectTimeout, Duration requestTimeout) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
    }

    public String getApiKey() { return apiKey; }
    public URI getBaseUrl() { return baseUrl; }
    public String getModelName() { return modelName; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
}
