package com.processing.e2e.tests;

import com.processing.e2e.support.ApiSpecifications;
import com.processing.e2e.support.ApiTestSupport;
import com.processing.e2e.support.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Expectations come from the switch, authorization and logger controllers. */
class InternalContractsApiTest extends ApiTestSupport {

    @Test
    void shouldRouteTransactionThroughInternalSwitchEndpoint() {
        // Given
        Map<String, Object> request = TestDataFactory.authorizationRequest(
                "9999991234567890", new BigDecimal("1000"), TestDataFactory.uniqueStan());

        // When
        Response response = given()
                .spec(ApiSpecifications.SWITCH)
                .body(request)
                .when()
                .post("/api/internal/route");

        // Then
        response.then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void shouldAuthorizeAndRollbackThroughInternalAuthorizationEndpoints() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        BigDecimal amount = new BigDecimal("1000");
        Map<String, Object> request = TestDataFactory.authorizationRequest(
                pan, amount, TestDataFactory.uniqueStan());

        // When
        Response authorized = given()
                .spec(ApiSpecifications.AUTHORIZATION)
                .body(request)
                .when()
                .post("/api/internal/authorize");

        // Then
        String rrn = authorized.then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("rrn", matchesPattern("^[0-9]{12}$"))
                .extract()
                .path("rrn");

        // When
        Response rollback = given()
                .spec(ApiSpecifications.AUTHORIZATION)
                .body(Map.of("rrn", rrn, "pan", pan, "amount", amount))
                .when()
                .post("/api/internal/rollback");

        // Then
        rollback.then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("declineReason", nullValue());
    }

    @Test
    void shouldReturnAuthorizationBusinessFailureWithHttpStatus() {
        // Given
        Map<String, Object> request = TestDataFactory.authorizationRequest(
                "9999990000000000", new BigDecimal("1000"), TestDataFactory.uniqueStan());

        // When
        Response response = given()
                .spec(ApiSpecifications.AUTHORIZATION)
                .body(request)
                .when()
                .post("/api/internal/authorize");

        // Then
        response.then()
                .statusCode(404)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void shouldReturnNotFoundWhenRollbackTransactionDoesNotExist() {
        // Given
        Map<String, Object> request = Map.of(
                "rrn", TestDataFactory.uniqueRrn(),
                "pan", "9999990000000000",
                "amount", 1000);

        // When
        Response response = given()
                .spec(ApiSpecifications.AUTHORIZATION)
                .body(request)
                .when()
                .post("/api/internal/rollback");

        // Then
        response.then()
                .statusCode(404)
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void shouldStoreSameLoggerMessageIdempotently() {
        // Given
        UUID id = UUID.randomUUID();
        String stan = TestDataFactory.uniqueStan();
        Map<String, Object> request = TestDataFactory.loggerRequest(id, stan);

        // When
        Response first = given()
                .spec(ApiSpecifications.LOGGER)
                .body(request)
                .when()
                .post("/api/internal/log");
        Response repeated = given()
                .spec(ApiSpecifications.LOGGER)
                .body(request)
                .when()
                .post("/api/internal/log");

        // Then
        first.then()
                .statusCode(201)
                .body("id", equalTo(id.toString()))
                .body("status", equalTo("stored"));
        repeated.then()
                .statusCode(200)
                .body("id", equalTo(id.toString()))
                .body("stan", equalTo(stan));
    }

    @Test
    void shouldRejectRepeatedLoggerIdWithDifferentData() {
        // Given
        UUID id = UUID.randomUUID();
        Map<String, Object> original = TestDataFactory.loggerRequest(id, TestDataFactory.uniqueStan());
        Map<String, Object> changed = new LinkedHashMap<>(original);
        changed.put("amount", 2000);
        given().spec(ApiSpecifications.LOGGER)
                .body(original)
                .when()
                .post("/api/internal/log")
                .then()
                .statusCode(201);

        // When
        Response response = given()
                .spec(ApiSpecifications.LOGGER)
                .body(changed)
                .when()
                .post("/api/internal/log");

        // Then
        response.then()
                .statusCode(409)
                .body("error", equalTo("Transaction conflict"))
                .body("message", containsString(id.toString()))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    void shouldRejectInvalidInternalLoggerRequest() {
        // Given
        Map<String, Object> request = TestDataFactory.loggerRequest(
                UUID.randomUUID(), TestDataFactory.uniqueStan());
        request.put("stan", "12");

        // When
        Response response = given()
                .spec(ApiSpecifications.LOGGER)
                .body(request)
                .when()
                .post("/api/internal/log");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", notNullValue());
    }

    @Test
    void shouldReturnKnownBinLookupContract() {
        // Given
        String bin = "400000";
        var request = given().spec(ApiSpecifications.BIN_LOOKUP);

        // When
        Response response = request.when().get("/api/bin/{bin}", bin);

        // Then
        response.then()
                .statusCode(200)
                .body("bin", equalTo(bin))
                .body("issuerId", notNullValue())
                .body("issuerName", notNullValue());
    }

    @Test
    void shouldReturnNotFoundForUnknownBin() {
        // Given
        var request = given().spec(ApiSpecifications.BIN_LOOKUP);

        // When
        Response response = request.when().get("/api/bin/999999");

        // Then
        response.then().statusCode(404);
    }

    @Test
    void shouldExposeBinLookupSimulatedFailure() {
        // Given
        var request = given().spec(ApiSpecifications.BIN_LOOKUP).queryParam("fail", true);

        // When
        Response response = request.when().get("/api/bin/400000");

        // Then
        response.then().statusCode(500);
    }
}
