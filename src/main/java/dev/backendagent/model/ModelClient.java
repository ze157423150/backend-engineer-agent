package dev.backendagent.model;

@FunctionalInterface
public interface ModelClient {
    ModelResponse execute(ModelRequest request);
}
