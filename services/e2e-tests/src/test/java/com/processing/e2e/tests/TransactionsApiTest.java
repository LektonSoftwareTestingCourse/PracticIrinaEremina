package com.processing.e2e.tests;

import com.processing.e2e.support.ApiSpecifications;
import com.processing.e2e.support.ApiTestSupport;
import com.processing.e2e.support.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;

/** Expectations come from docs/api/openapi.yaml, Gateway routes and transaction controllers. */
class TransactionsApiTest extends ApiTestSupport {

    @Test
    void shouldReturnGatewayAndDependencyHealth() {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY);

        // When
        Response response = request.when().get("/health");

        // Then
        response.then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("services.cardManagement", equalTo("ok"))
                .body("services.authorization", equalTo("ok"))
                .body("services.logger", equalTo("ok"))
                .body("services.switch", equalTo("ok"));
    }

    @Test
    void shouldApproveTransactionAndReturnGeneratedValuesInContractFormat() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        String stan = TestDataFactory.uniqueStan();

        // When
        Response response = authorize(pan, new BigDecimal("1000"), stan);

        // Then
        response.then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("^[0-9]{12}$"))
                .body("authCode", matchesPattern("^[A-Z0-9]{6}$"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    @ParameterizedTest(name = "gateway validation field {0}")
    @MethodSource("invalidTransactionRequests")
    void shouldRejectEachInvalidTransactionClass(String field, Object invalidValue, String expectedMessage) {
        // Given
        Map<String, Object> request = new LinkedHashMap<>(TestDataFactory.authorizationRequest(
                "4000001234567890", new BigDecimal("1000"), TestDataFactory.uniqueStan()));
        request.put(field, invalidValue);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/transactions");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo(expectedMessage))
                .body("serviceName", equalTo("gateway"));
    }

    @Test
    void shouldDeclineUnknownBinWithDomainCodeAndReason() {
        // Given
        String stan = TestDataFactory.uniqueStan();

        // When
        Response response = authorize("9999991234567890", new BigDecimal("1000"), stan);

        // Then
        response.then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("rrn", nullValue());
    }

    @Test
    void shouldDeclineWhenCardBalanceIsInsufficient() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");

        // When
        Response response = authorize(pan, new BigDecimal("500001"), TestDataFactory.uniqueStan());

        // Then
        response.then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    void shouldDeclineBlockedCardThroughIndependentStatePath() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        given().spec(ApiSpecifications.GATEWAY)
                .body(Map.of("status", "BLOCKED"))
                .when()
                .patch("/api/cards/{pan}", pan)
                .then()
                .statusCode(200);

        // When
        Response response = authorize(pan, new BigDecimal("1000"), TestDataFactory.uniqueStan());

        // Then
        response.then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", oneOf("54", "05"))
                .body("declineReason", equalTo("CARD_BLOCKED"));
    }

    @ParameterizedTest(name = "card status {0} declines with {2}")
    @CsvSource({
            "BLOCKED, 05, CARD_BLOCKED",
            "INACTIVE, 05, CARD_INACTIVE",
            "EXPIRED, 54, CARD_EXPIRED"
    })
    void shouldDeclineForEachNonActiveCardState(String cardStatus, String code, String reason) {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        given().spec(ApiSpecifications.GATEWAY)
                .body(Map.of("status", cardStatus))
                .when()
                .patch("/api/cards/{pan}", pan)
                .then()
                .statusCode(200);

        // When
        Response response = authorize(pan, new BigDecimal("1000"), TestDataFactory.uniqueStan());

        // Then
        response.then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason));
    }

    @Test
    void shouldDeclineWhenConfiguredDailyLimitIsExceeded() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        given().spec(ApiSpecifications.GATEWAY)
                .body(Map.of("dailyLimit", 500, "monthlyLimit", 500))
                .when()
                .patch("/api/cards/{pan}", pan)
                .then()
                .statusCode(200);

        // When
        Response response = authorize(pan, new BigDecimal("1000"), TestDataFactory.uniqueStan());

        // Then
        response.then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @Test
    void shouldStoreGatewayTransactionAndFindItWithGPathFilter() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        String stan = TestDataFactory.uniqueStan();

        // When
        Response authorization = authorize(pan, new BigDecimal("1000"), stan);

        // Then
        authorization.then().statusCode(200);
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given()
                        .spec(ApiSpecifications.GATEWAY)
                        .queryParam("pan", pan)
                        .when()
                        .get("/api/transactions/search")
                        .then()
                        .statusCode(200)
                        .body("transactions.findAll { it.stan == '%s' }".formatted(stan), hasSize(1))
                        .body("transactions.find { it.stan == '%s' }.pan".formatted(stan), equalTo(pan)));
    }

    @ParameterizedTest(name = "transaction search filter {0}")
    @CsvSource({
            "status, APPROVED",
            "merchantId, MERCH0000000001",
            "issuerId, ISS001",
            "mcc, 5411"
    })
    void shouldFindOwnTransactionByEachSupportedBusinessFilter(String parameter, String value) {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        String stan = TestDataFactory.uniqueStan();
        authorize(pan, new BigDecimal("1000"), stan).then().statusCode(200);

        // When and Then
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given()
                        .spec(ApiSpecifications.GATEWAY)
                        .queryParam(parameter, value)
                        .queryParam("limit", 500)
                        .when()
                        .get("/api/transactions/search")
                        .then()
                        .statusCode(200)
                        .body("transactions.findAll { it.pan == '%s' }".formatted(pan), hasSize(1)));
    }

    @Test
    void shouldApplyTransactionDateRangeAndPaginationTogether() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        String stan = TestDataFactory.uniqueStan();
        authorize(pan, new BigDecimal("1000"), stan).then().statusCode(200);

        // When and Then
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given()
                        .spec(ApiSpecifications.GATEWAY)
                        .queryParam("dateFrom", LocalDate.now().minusDays(1).toString())
                        .queryParam("dateTo", LocalDate.now().plusDays(1).toString())
                        .queryParam("limit", 500)
                        .queryParam("offset", 0)
                        .when()
                        .get("/api/transactions/search")
                        .then()
                        .statusCode(200)
                        .body("transactions.findAll { it.pan == '%s' }".formatted(pan), hasSize(1)));
    }

    @Test
    void shouldFilterDeclinedTransactionsByReason() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        String stan = TestDataFactory.uniqueStan();
        given().spec(ApiSpecifications.GATEWAY)
                .body(Map.of("status", "BLOCKED"))
                .when()
                .patch("/api/cards/{pan}", pan)
                .then()
                .statusCode(200);
        authorize(pan, new BigDecimal("1000"), stan).then().statusCode(200);

        // When and Then
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given()
                        .spec(ApiSpecifications.GATEWAY)
                        .queryParam("declineReason", "CARD_BLOCKED")
                        .queryParam("limit", 500)
                        .when()
                        .get("/api/transactions/search")
                        .then()
                        .statusCode(200)
                        .body("transactions.findAll { it.pan == '%s' }".formatted(pan), hasSize(1)));
    }

    @Test
    void shouldReturnEmptyTransactionSelection() {
        // Given
        var request = given()
                .spec(ApiSpecifications.GATEWAY)
                .queryParam("merchantId", "NO-SUCH-MERCHANT")
                .queryParam("issuerId", "NO-SUCH-ISSUER");

        // When
        Response response = request.when().get("/api/transactions/search");

        // Then
        response.then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", hasSize(0));
    }

    @Test
    void shouldKeepInvalidTransactionAbsentFromStorage() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        String stan = TestDataFactory.uniqueStan();
        Map<String, Object> request = TestDataFactory.authorizationRequest(
                pan, BigDecimal.ZERO, stan);

        // When
        given().spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(400);

        // Then
        await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(4))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given()
                        .spec(ApiSpecifications.GATEWAY)
                        .queryParam("pan", pan)
                        .when()
                        .get("/api/transactions/search")
                        .then()
                        .statusCode(200)
                        .body("total", equalTo(0)));
    }

    @ParameterizedTest(name = "invalid search {0}={1}")
    @CsvSource({
            "limit, 0",
            "limit, 501",
            "offset, -1"
    })
    void shouldRejectInvalidSearchPagination(String parameter, int value) {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY).queryParam(parameter, value);

        // When
        Response response = request.when().get("/api/transactions/search");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", notNullValue());
    }

    @Test
    void shouldExportTransactionsAsCsvAttachment() {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY).queryParam("status", "APPROVED");

        // When
        Response response = request.when().get("/api/transactions/export");

        // Then
        response.then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .body(containsString("id,mti,stan,rrn,pan"));
    }

    private static Stream<Arguments> invalidTransactionRequests() {
        return Stream.of(
                Arguments.of("mti", "0200", "Field 'mti' must be '0100'"),
                Arguments.of("pan", "400000123", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("amount", null, "Field 'amount' is required"),
                Arguments.of("amount", 0, "Field 'amount' must be > 0"),
                Arguments.of("currencyCode", "64", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc", "54A1", "Field 'mcc' must be exactly 4 digits")
        );
    }
}
