package io.quarkus.it.extension;

import java.io.IOException;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import io.quarkus.extest.runtime.RemovedResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
public class RemovedResourcesTest {
    String getHost() {
        return ""; // default host configured for RestAssured
    }

    @Test
    void removedResourceOwnClassLoader() throws IOException {
        RestAssured.given()
                .queryParam("resource", RemovedResource.COMMON_NET_MESSAGES.name())
                .queryParam("classLoaderKind", RemovedResource.ClassLoaderKind.OWN_CLASS_LOADER.name())
                .get(getHost() + "/core/removed-resource")
                .then().body(Matchers.not(Matchers.containsString("invalidAddress")));
    }

    @Test
    void removedResourceContextClassLoader() throws IOException {
        RestAssured.given()
                .queryParam("resource", RemovedResource.COMMON_NET_MESSAGES.name())
                .queryParam("classLoaderKind", RemovedResource.ClassLoaderKind.CONTEXT_CLASS_LOADER.name())
                .get(getHost() + "/core/removed-resource")
                .then().body(Matchers.not(Matchers.containsString("invalidAddress")));
    }

    @Test
    void replacedResourceOwnClassLoader() throws IOException {
        RestAssured.given()
                .queryParam("resource", RemovedResource.COMMON_IO_MESSAGES.name())
                .queryParam("classLoaderKind", RemovedResource.ClassLoaderKind.OWN_CLASS_LOADER.name())
                .get(getHost() + "/core/removed-resource")
                .then().body(Matchers.containsString("newKey=newValue"));
    }

    @Test
    void replacedResourceContextClassLoader() throws IOException {
        RestAssured.given()
                .queryParam("resource", RemovedResource.COMMON_IO_MESSAGES.name())
                .queryParam("classLoaderKind", RemovedResource.ClassLoaderKind.CONTEXT_CLASS_LOADER.name())
                .get(getHost() + "/core/removed-resource")
                .then().body(Matchers.containsString("newKey=newValue"));
    }
}
