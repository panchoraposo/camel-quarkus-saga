package com.redhat.demo.camel.saga;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;

@QuarkusTest
class AllocateResourceTest {
    @Test
    void seatsEndpointShouldReturnSeats() {
        given()
          .when().get("/seats")
          .then()
             .statusCode(200)
             .body("size()", greaterThan(0));
    }

}