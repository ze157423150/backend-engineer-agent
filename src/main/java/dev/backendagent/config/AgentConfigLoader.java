package dev.backendagent.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

public final class AgentConfigLoader {
    private AgentConfigLoader() {}

    public static ModelConfig load(Path localFile) {
        return load(localFile, System.getenv());
    }

    // Environment passed explicitly here so configuration tests need no real credentials.
    static ModelConfig load(Path localFile, Map<String, String> environment) {
        Properties properties = new Properties();
        try (var stream = AgentConfigLoader.class.getResourceAsStream("/agent.properties")) {
            if (stream == null) {
                throw new IllegalStateException("Missing default agent.properties resource");
            }
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read default configuration");
        }

        applyEnvironment(properties, environment, "DEEPSEEK_BASE_URL", "model.base-url");
        applyEnvironment(properties, environment, "DEEPSEEK_MODEL", "model.name");
        Path selectedFile = localFile == null ? Path.of("agent-local.properties") : localFile;
        if (localFile != null || Files.exists(selectedFile)) {
            try (var reader = Files.newBufferedReader(selectedFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException failure) {
                // A properties parse error can contain a secret: report without its raw message.
                throw new IllegalArgumentException("Cannot read local configuration file");
            }
        }

        String apiKey = properties.getProperty("model.api-key", "").trim();
        if (apiKey.isEmpty()) {
            apiKey = environment.getOrDefault(required(properties, "model.api-key-env"), "").trim();
        }
        if (apiKey.isBlank() || apiKey.contains("\n") || apiKey.contains("\r")) {
            throw new IllegalArgumentException("Fill model.api-key in agent-local.properties, or set the API key environment variable");
        }
        URI baseUrl;
        try {
            baseUrl = URI.create(required(properties, "model.base-url"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid model.base-url");
        }
        return new ModelConfig(apiKey, baseUrl, required(properties, "model.name"),
                seconds(properties, "model.connect-timeout-seconds"),
                seconds(properties, "model.request-timeout-seconds"));
    }

    private static void applyEnvironment(Properties properties, Map<String, String> environment,
                                         String variable, String property) {
        String value = environment.get(variable);
        if (value != null && !value.isBlank()) {
            properties.setProperty(property, value);
        }
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration: " + name);
        }
        return value.trim();
    }

    private static Duration seconds(Properties properties, String name) {
        try {
            long value = Long.parseLong(required(properties, name));
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return Duration.ofSeconds(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Configuration must be positive integer seconds: " + name);
        }
    }
}
