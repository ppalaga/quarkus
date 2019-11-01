package io.quarkus.arc.test;

public interface Endpoint {
    String getId();

    String handle(String input);
}
