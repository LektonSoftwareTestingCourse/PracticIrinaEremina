package com.processing.e2e.tests;

import com.processing.e2e.support.ApiSpecifications;
import com.processing.e2e.support.ApiTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/** Expectations come from Gateway routes plus dashboard and simulator controllers. */
class SupportingEndpointsApiTest extends ApiTestSupport {

    @Test
    void shouldReturnDashboardStatistics() {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY);

        // When
        Response response = request.when().get("/api/dashboard/stats");

        // Then
        response.then()
                .statusCode(200)
                .body("totalTransactions", greaterThanOrEqualTo(0))
                .body("approvedCount", greaterThanOrEqualTo(0))
                .body("declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", greaterThanOrEqualTo(0.0F))
                .body("approvalRate", lessThanOrEqualTo(1.0F));
    }

    @Test
    void shouldReturnRecentTransactionsWithRequestedLimit() {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY).queryParam("limit", 2);

        // When
        Response response = request.when().get("/api/dashboard/recent");

        // Then
        response.then()
                .statusCode(200)
                .body("$", hasSize(lessThanOrEqualTo(2)));
    }

    @ParameterizedTest(name = "invalid recent limit {0}")
    @ValueSource(ints = {0, 501})
    void shouldRejectInvalidRecentTransactionLimit(int limit) {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY).queryParam("limit", limit);

        // When
        Response response = request.when().get("/api/dashboard/recent");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void shouldReturnDashboardChartsForSupportedGranularity(String granularity) {
        // Given
        var request = given()
                .spec(ApiSpecifications.GATEWAY)
                .queryParam("granularity", granularity);

        // When
        Response response = request.when().get("/api/dashboard/charts");

        // Then
        response.then()
                .statusCode(200)
                .body("$", notNullValue());
    }

    @Test
    void shouldRejectUnsupportedChartGranularity() {
        // Given
        var request = given()
                .spec(ApiSpecifications.GATEWAY)
                .queryParam("granularity", "minute");

        // When
        Response response = request.when().get("/api/dashboard/charts");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", containsString("granularity"));
    }

    @Test
    void shouldRunOneTerminalSimulation() {
        // Given
        Map<String, Object> request = Map.of("count", 1, "scenario", "normal", "tps", 1);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/simulator/terminal/run");

        // Then
        response.then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(1))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("transactions", hasSize(1));
    }

    @ParameterizedTest(name = "invalid terminal run count {0}, tps {1}")
    @CsvSource({"0, 1", "1, 0"})
    void shouldRejectInvalidTerminalRunBoundaries(int count, int tps) {
        // Given
        Map<String, Object> request = Map.of("count", count, "scenario", "normal", "tps", tps);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/simulator/terminal/run");

        // Then
        response.then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    @Test
    void shouldStartAndStopContinuousTerminalMode() {
        // Given
        Map<String, Object> request = Map.of("tps", 1, "transactionType", "NORMAL");

        // When
        Response started = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/simulator/terminal/start-continuous");
        Response stopped = given()
                .spec(ApiSpecifications.GATEWAY)
                .when()
                .post("/api/simulator/terminal/stop");

        // Then
        started.then().statusCode(200);
        stopped.then().statusCode(200);
    }

    @Test
    void shouldDocumentMissingTerminalStatusEndpointFromPublishedSpec() {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY);

        // When
        Response response = request.when().get("/api/simulator/terminal/status");

        // Then
        response.then().statusCode(500);
    }

    @Test
    void shouldExposeMerchantListDirectlyWhileGatewayRouteIsMissing() {
        // Given
        var gatewayRequest = given().spec(ApiSpecifications.GATEWAY);

        // When
        Response gatewayResponse = gatewayRequest.when().get("/api/simulator/merchants");
        Response directResponse = given()
                .spec(ApiSpecifications.MERCHANT)
                .when()
                .get("/api/simulator/merchants");

        // Then
        gatewayResponse.then().statusCode(404);
        directResponse.then()
                .statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .body("[0].id", notNullValue())
                .body("[0].mcc", notNullValue());
    }

    @Test
    void shouldRunMerchantSimulationThroughGateway() {
        // Given
        Map<String, Object> request = Map.of(
                "count", 1,
                "scenario", "grocery",
                "tps", 1);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/simulator/merchant/run");

        // Then
        response.then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(1))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("transactions", hasSize(1));
    }

    @ParameterizedTest(name = "invalid merchant run count {0}, tps {1}")
    @CsvSource({"0, 1", "1, 0"})
    void shouldRejectInvalidMerchantSimulationBoundaries(int count, int tps) {
        // Given
        Map<String, Object> request = Map.of(
                "count", count,
                "scenario", "grocery",
                "tps", tps);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/simulator/merchant/run");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("Invalid request"))
                .body("message", notNullValue());
    }

    @Test
    void shouldRejectUnknownMerchantFee() {
        // Given
        Map<String, Object> request = Map.of(
                "transmissionDateTime", Instant.now().toString(),
                "pan", "4000001234567890",
                "stan", "999999",
                "amount", 1000,
                "terminalId", "TERM0001");

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/simulator/merchant/fee");

        // Then
        response.then()
                .statusCode(404)
                .body("error", equalTo("Resource not found"))
                .body("message", containsString("999999"));
    }

    @Test
    void shouldDeliverCardNotificationAsynchronously() {
        // Given
        Instant startedAt = Instant.now();
        int initialCount = given()
                .spec(ApiSpecifications.NOTIFICATION)
                .queryParam("size", 1)
                .when()
                .get("/api/notifications")
                .then()
                .statusCode(200)
                .extract()
                .path("totalElements");

        // When
        createCard().then().statusCode(201);

        // Then
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given()
                        .spec(ApiSpecifications.NOTIFICATION)
                        .queryParam("size", 100)
                        .when()
                        .get("/api/notifications")
                        .then()
                        .statusCode(200)
                        .body("totalElements", greaterThan(initialCount))
                        .body("content.findAll { it.eventType == 'CardServiceCreationEvent' "
                                        + "&& it.receivedAt >= '%s' }".formatted(startedAt),
                                hasSize(greaterThan(0))));
    }
}
