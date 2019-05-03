package io.quarkus.it.camel.undertow;

import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * Test connecting Hibernate ORM to H2.
 * The H2 database server is run in JVM mode, the Hibernate based application
 * is run in both JVM mode and native mode (see also test in subclass).
 */
@QuarkusTest
public class CamelUndertowTest {

    @Test
    public void hello() throws Exception {
        RestAssured.when().get("/hello").then().body(is("Hello"));
    }

    @Test
    public void stockServlet() throws Exception {
        RestAssured.when().get("/stock-servlet").then().body(is("GET /stock-servlet"));
        RestAssured.when().get("/stock-servlet/sub-path").then().body(is("GET /stock-servlet/sub-path"));
    }

    @Test
    public void badPath() throws Exception {
        RestAssured.when().get("/non-existent-path").then().statusCode(404);
    }

}
