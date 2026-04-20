package com.redhat.demo.camel.saga;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@QuarkusTest
class PaymentResourceTest {
    @Test
    void paymentsEndpointShouldReturnJsonArray() {
        given()
          .when().get("/payments")
          .then()
             .statusCode(200)
             .body("size()", greaterThanOrEqualTo(0));
    }

}