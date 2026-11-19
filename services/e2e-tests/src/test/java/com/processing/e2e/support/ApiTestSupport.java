package com.processing.e2e.support;

import io.restassured.response.Response;

import java.math.BigDecimal;
import java.util.Map;

import static io.restassured.RestAssured.given;

public abstract class ApiTestSupport {

    protected Response createCard() {
        return given()
                .spec(ApiSpecifications.GATEWAY)
                .body(TestDataFactory.cardRequest())
                .when()
                .post("/api/cards");
    }

    protected Response authorize(String pan, BigDecimal amount, String stan) {
        Map<String, Object> body = TestDataFactory.authorizationRequest(pan, amount, stan);
        return given()
                .spec(ApiSpecifications.GATEWAY)
                .body(body)
                .when()
                .post("/api/transactions");
    }
}
