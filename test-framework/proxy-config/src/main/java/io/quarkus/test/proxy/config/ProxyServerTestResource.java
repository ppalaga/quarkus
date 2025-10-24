package io.quarkus.test.proxy.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

/**
 * A basic HTTP proxy server for testing.
 * <p>
 * Features:
 * <ul>
 * <li>Inspecting the requests it has proxied; just send a GET request to any path and it returns a JSON array</li>
 * <li>Basic HTTP authentication:</li>
 * <ul>
 * <li>Username and password can be set either programmatically via {@link #init(Map)} or using
 * {@link QuarkusTestResource#initArgs()}; use keys {@code username} and {@code password}</li>
 * <li>If username and password are not set, the proxy will allow anonymous requests</li>
 * </ul>
 * </ul>
 */
public final class ProxyServerTestResource implements QuarkusTestResourceLifecycleManager, AutoCloseable {

    public static final String QUARKUS_TEST_PROXY_PASSWORD = "quarkus.test.proxy.password";
    public static final String QUARKUS_TEST_PROXY_USERNAME = "quarkus.test.proxy.username";
    public static final String QUARKUS_TEST_PROXY_PORT = "quarkus.test.proxy.port";
    public static final String QUARKUS_TEST_PROXY_HOST = "quarkus.test.proxy.host";

    private static final Logger log = Logger.getLogger(ProxyServerTestResource.class);

    private volatile int port = 0;
    private String username;
    private String password;
    private final Vertx vertx;
    private final HttpServer proxyServer;
    private final List<String> proxiedRequests = new ArrayList<>();

    public ProxyServerTestResource() {
        this.vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1).setEventLoopPoolSize(1));
        this.proxyServer = vertx.createHttpServer();
    }

    public void init(Map<String, String> initArgs) {
        if (initArgs.containsKey("port")) {
            this.port = Integer.parseInt(initArgs.get("port"));
        }
        if (initArgs.containsKey("username")) {
            this.username = initArgs.get("username");
        }
        if (initArgs.containsKey("password")) {
            this.password = initArgs.get("password");
        }
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public Map<String, String> start() {
        CountDownLatch startLatch = new CountDownLatch(1);
        proxyServer.requestHandler(this::handle);
        proxyServer.listen(port).onComplete(result -> {
            port = result.result().actualPort();
            log.infof("HTTP proxy server started on port %d", port);
            startLatch.countDown();
        });
        try {
            startLatch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put(QUARKUS_TEST_PROXY_HOST, "localhost");
        result.put(QUARKUS_TEST_PROXY_PORT, String.valueOf(port));
        if (username != null) {
            result.put(QUARKUS_TEST_PROXY_USERNAME, username);
        }
        if (password != null) {
            result.put(QUARKUS_TEST_PROXY_PASSWORD, password);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void stop() {
        if (proxyServer != null) {
            log.info("HTTP proxy server shutting down");
            proxyServer.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    void handle(HttpServerRequest httpServerRequest) {
        log.infof("proxy handling");

        String authorization = httpServerRequest.getHeader("Proxy-Authorization");
        HttpServerResponse response = httpServerRequest.response();
        HttpMethod method = httpServerRequest.method();

        String host = httpServerRequest.getHeader("Host");
        String[] hostParts = host.split(":");
        final String remoteHost = hostParts[0];
        final int remotePort = Integer.parseInt(hostParts[1]);

        if (password != null && method.equals(HttpMethod.CONNECT) && authorization == null) {
            response.putHeader("Proxy-Authenticate", "Basic")
                    .setStatusCode(407)
                    .end();
            return;
        } else if (method.equals(HttpMethod.GET) && "localhost".equals(remoteHost) && remotePort == port) {
            /* Output the proxy log */
            List<String> vals = new ArrayList<>();
            synchronized (proxiedRequests) {
                vals.addAll(proxiedRequests);
                proxiedRequests.clear();
            }
            httpServerRequest.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonArray(vals).encodePrettily());
            return;
        }

        if (password != null) {
            String[] authParts = authorization.split(" ");
            String[] credentials = new String(Base64.getDecoder().decode(authParts[1])).split(":");
            if (credentials.length != 2) {
                response.setStatusCode(400).end();
                return;
            } else {
                if (credentials[0].equals(username) && credentials[1].equals(password)) {
                    proxy(httpServerRequest, remoteHost, remotePort);
                }
                return;
            }
        } else {
            /* No username and password set */
            proxy(httpServerRequest, remoteHost, remotePort);
            return;
        }
    }

    private void proxy(HttpServerRequest httpServerRequest, String remoteHost, int remotePort) {
        HttpMethod method = httpServerRequest.method();
        if (method.equals(HttpMethod.CONNECT)) {
            // Deal with the result of the CONNECT tunnel and proxy the request / response
            NetClient netClient = vertx.createNetClient();
            log.infof("Connect %s:%s", remoteHost, remotePort);
            netClient.connect(remotePort, remoteHost, result -> {
                if (result.succeeded()) {
                    NetSocket clientSocket = result.result();
                    Future<NetSocket> netSocket = httpServerRequest.toNetSocket();
                    NetSocket serverSocket = netSocket.result();
                    serverSocket.closeHandler(v -> clientSocket.close());
                    clientSocket.closeHandler(v -> serverSocket.close());
                    serverSocket.pipeTo(clientSocket);
                    clientSocket.pipeTo(serverSocket);
                } else {
                    httpServerRequest.response().setStatusCode(403).end();
                }
            });
        } else {
            // non-CONNECT

            httpServerRequest.body().onSuccess(httpServerBody -> {
                log.infof("Proxying to %s %s", httpServerRequest.uri(), httpServerBody);
                synchronized (proxiedRequests) {
                    proxiedRequests.add(method + " " + httpServerRequest.uri());
                }
                HttpClient client = vertx.createHttpClient();
                MultiMap remoteHeaders = new HeadersMultiMap();
                remoteHeaders.addAll(httpServerRequest.headers());
                remoteHeaders.remove("Proxy-Authorization");
                client.request(
                        new RequestOptions()
                                .setMethod(method)
                                .setHeaders(remoteHeaders)
                                .setPort(remotePort)
                                .setHost(remoteHost)
                                .setURI(httpServerRequest.uri()))
                        .onSuccess(remoteRequest -> {
                            remoteRequest.end(httpServerBody);
                            remoteRequest.response()
                                    .onSuccess(remoteResponse -> {
                                        // Forward the response status and headers
                                        HttpServerResponse httpServerResponse = httpServerRequest.response();
                                        httpServerResponse.setStatusCode(remoteResponse.statusCode());
                                        httpServerResponse.headers().setAll(remoteResponse.headers());

                                        // Pipe the response body
                                        remoteResponse.body()
                                                .onSuccess(body -> {
                                                    httpServerResponse.end(body);
                                                }).onFailure(err -> {
                                                    log.errorf(err, "Could not receive body from %s to %:%s %s",
                                                            method, remoteHost,
                                                            remotePort, httpServerRequest.uri());
                                                    httpServerResponse.setStatusCode(500)
                                                            .end("Internal Server Error ");
                                                });
                                    })
                                    .onFailure(err -> {
                                        log.errorf(err, "Could not receive response from %s to %:%s %s", method,
                                                remoteHost,
                                                remotePort, httpServerRequest.uri());
                                        httpServerRequest.response().setStatusCode(500)
                                                .end("Internal Server Error ");
                                    });
                        })
                        .onFailure(remoteError -> {
                            log.errorf(remoteError, "Could not send request to %s to %:%s %s", method,
                                    remoteHost,
                                    remotePort, httpServerRequest.uri());
                            httpServerRequest.response().setStatusCode(500)
                                    .end("Internal Server Error ");

                        });
            })
                    .onFailure(err -> {
                        log.errorf(err, "Could not recevie the body from the proxy server client %s to %:%s %s",
                                method, remoteHost,
                                remotePort, httpServerRequest.uri());
                        httpServerRequest.response().setStatusCode(500)
                                .end("Internal Server Error ");
                    });
        }
    }
}
