package io.quarkus.test.proxy.config;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;
import io.restassured.specification.ProxySpecification;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;

public class ProxyServerTestResourceTest {

    @Test
    void anonymous() {
        proxy(null, null);
    }

    @Test
    void basicAuth() {
        proxy("user", "123");
    }

    void proxy(String username, String password) {
        final Vertx vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1).setEventLoopPoolSize(1));
        final HttpServer server = vertx.createHttpServer();

        try {
            final AtomicInteger port = new AtomicInteger();
            final CountDownLatch startLatch = new CountDownLatch(1);
            server.requestHandler(req -> {
                if (req.method().equals(HttpMethod.GET)) {
                    req.response()
                            .putHeader("Content-Type", "text/plain")
                            .end("Hello from " + req.path());
                } else {
                    req.response().setStatusCode(405).end();
                }
            });
            server.listen(0).onComplete(result -> {
                port.set(result.result().actualPort());
                startLatch.countDown();
            });
            try {
                startLatch.await(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            try (ProxyServerTestResource proxy = new ProxyServerTestResource()) {
                if (username != null) {
                    proxy.init(Map.of("username", username, "password", password));
                }

                final Map<String, String> config = proxy.start();
                final int proxyPort = Integer.parseInt(config.get(ProxyServerTestResource.QUARKUS_TEST_PROXY_PORT));
                final String proxyHost = config.get(ProxyServerTestResource.QUARKUS_TEST_PROXY_HOST);

                /* First make sure we can connect through the proxy */
                RestAssured.given()
                        .get("http://localhost:" + port.get() + "/direct")
                        .then()
                        .statusCode(200)
                        .body(Matchers.equalTo("Hello from /direct"));

                ProxySpecification proxySpec = ProxySpecification.host(proxyHost).port(proxyPort);
                if (username != null) {
                    proxySpec = proxySpec.withAuth(username, password);
                }
                /* Then try with proxy */
                RestAssured.given()
                        .proxy(proxySpec)
                        .get("http://localhost:" + port.get() + "/through-proxy")
                        .then()
                        .statusCode(200)
                        .body(Matchers.equalTo("Hello from /through-proxy"));

                /* Make sure the request went through the proxy */
                RestAssured.given()
                        .get("http://localhost:" + proxyPort)
                        .then()
                        .statusCode(200)
                        .body("", Matchers.hasSize(1))
                        .body("[0]", Matchers.equalTo("GET http://localhost:" + port.get() + "/through-proxy"));

            }
        } finally {
            server.close();
            vertx.close();
        }

    }

}
