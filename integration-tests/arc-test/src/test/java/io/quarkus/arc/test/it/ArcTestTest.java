package io.quarkus.arc.test.it;

import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
class ArcTestTest {

    @Test
    public void routeBuilderInjected() {
        RestAssured.when()
                .post("/arc-test/foo")
                .then()
                .statusCode(200)
                .body(equalTo("foo (1)"));

        RestAssured.when()
                .get("/arc-test/instance-count")
                .then()
                .statusCode(200)
                .body(equalTo("1"));
    }

}
