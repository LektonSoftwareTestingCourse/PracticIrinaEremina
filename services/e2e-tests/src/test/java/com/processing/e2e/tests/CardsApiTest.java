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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

/** Expectations come from docs/api/openapi.yaml and CardController. */
class CardsApiTest extends ApiTestSupport {

    @Test
    void shouldCreateCardMatchingPublishedContract() {
        // Given
        Map<String, Object> request = TestDataFactory.cardRequest();

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/cards");

        // Then
        response.then()
                .statusCode(201)
                .contentType(containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/card-response.schema.json"))
                .body("pan", matchesPattern("^[0-9]{16}$"))
                .body("bin", equalTo("400000"))
                .body("status", equalTo("ACTIVE"))
                .body("availableBalance", equalTo(500000));
    }

    @ParameterizedTest(name = "invalid card field {0}")
    @MethodSource("invalidCardRequests")
    void shouldReturnFieldErrorWhenCreateCardRequestIsInvalid(String field, Object invalidValue) {
        // Given
        Map<String, Object> request = new LinkedHashMap<>(TestDataFactory.cardRequest());
        request.put(field, invalidValue);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/cards");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", notNullValue())
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void shouldReturnCreatedCardByPan() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .when()
                .get("/api/cards/{pan}", pan);

        // Then
        response.then()
                .statusCode(200)
                .body("pan", equalTo(pan))
                .body("id", notNullValue());
    }

    @Test
    void shouldReturnContractErrorWhenCardDoesNotExist() {
        // Given
        String unknownPan = "9999990000000000";

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .when()
                .get("/api/cards/{pan}", unknownPan);

        // Then
        response.then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"))
                .body("message", containsString("9999********0000"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void shouldFilterAndPaginateCards() {
        // Given
        createCard().then().statusCode(201);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .queryParam("bin", "400000")
                .queryParam("status", "ACTIVE")
                .queryParam("limit", 2)
                .queryParam("offset", 0)
                .when()
                .get("/api/cards");

        // Then
        response.then()
                .statusCode(200)
                .body("total", is(lessThanOrEqualTo(Integer.MAX_VALUE)))
                .body("cards", hasSize(lessThanOrEqualTo(2)))
                .body("cards.bin", everyItem(equalTo("400000")))
                .body("cards.status", everyItem(equalTo("ACTIVE")));
    }

    @ParameterizedTest(name = "invalid query {0}={1}")
    @CsvSource({
            "limit, 0",
            "offset, -1",
            "bin, 40000",
            "issuerId, issuer-lowercase"
    })
    void shouldRejectInvalidCardQueryParameter(String parameter, String value) {
        // Given
        var request = given().spec(ApiSpecifications.GATEWAY).queryParam(parameter, value);

        // When
        Response response = request.when().get("/api/cards");

        // Then
        response.then()
                .statusCode(400)
                .body("error", equalTo("ConstraintViolationException"))
                .body("message", notNullValue());
    }

    @Test
    void shouldPatchCardStateAndLimits() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        Map<String, Object> patch = Map.of(
                "status", "BLOCKED",
                "dailyLimit", 200000,
                "monthlyLimit", 2000000,
                "availableBalance", 400000);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(patch)
                .when()
                .patch("/api/cards/{pan}", pan);

        // Then
        response.then()
                .statusCode(200)
                .body("pan", equalTo(pan))
                .body("status", equalTo("BLOCKED"))
                .body("dailyLimit", equalTo(200000))
                .body("monthlyLimit", equalTo(2000000));
    }

    @Test
    void shouldDeleteCardAndReturnNoContent() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .when()
                .delete("/api/cards/{pan}", pan);

        // Then
        response.then().statusCode(204);
    }

    @Test
    void shouldReserveAndRollbackFundsThroughCardApi() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        String rrn = TestDataFactory.uniqueRrn();
        Map<String, Object> reservation = Map.of("amount", 1000, "rrn", rrn);

        // When
        Response reserved = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(reservation)
                .when()
                .post("/api/cards/{pan}/reserve", pan);

        // Then
        reserved.then()
                .statusCode(200)
                .body("availableBalance", equalTo(499000));

        // When
        Response rolledBack = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(Map.of("rrn", rrn, "pan", pan, "amount", 1000))
                .when()
                .post("/api/cards/{pan}/rollback", pan);

        // Then
        rolledBack.then()
                .statusCode(200)
                .body("availableBalance", equalTo(500000));
    }

    @Test
    void shouldRejectReservationWhenFundsAreInsufficient() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        Map<String, Object> request = Map.of(
                "amount", new BigDecimal("500001"),
                "rrn", TestDataFactory.uniqueRrn());

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/cards/{pan}/reserve", pan);

        // Then
        response.then()
                .statusCode(402)
                .body("error", equalTo("InsufficientFundsException"))
                .body("message", notNullValue());
    }

    @Test
    void shouldGenerateRequestedNumberOfCards() {
        // Given
        Map<String, Object> request = Map.of("count", 2, "bins", new String[]{"400000"});

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/cards/generate");

        // Then
        response.then()
                .statusCode(201)
                .body("generated", equalTo(2))
                .body("cards", hasSize(2))
                .body("cards.pan", everyItem(matchesPattern("^[0-9]{16}$")));
    }

    @Test
    void shouldBulkUpdateOnlySelectedCard() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        Map<String, Object> request = Map.of(
                "pans", new String[]{pan},
                "status", "INACTIVE");

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/cards/bulk-update");

        // Then
        response.then()
                .statusCode(200)
                .body("updated", equalTo(1));
        given().spec(ApiSpecifications.GATEWAY)
                .when()
                .get("/api/cards/{pan}", pan)
                .then()
                .statusCode(200)
                .body("status", equalTo("INACTIVE"));
    }

    @Test
    void shouldReturnEmptyPageForFilterWithoutMatches() {
        // Given
        var request = given()
                .spec(ApiSpecifications.GATEWAY)
                .queryParam("issuerId", "ZZZZZZ")
                .queryParam("status", "ACTIVE")
                .queryParam("limit", 10)
                .queryParam("offset", 0);

        // When
        Response response = request.when().get("/api/cards");

        // Then
        response.then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("cards", hasSize(0));
    }

    @Test
    void shouldRejectDuplicateReservationRrn() {
        // Given
        String pan = createCard().then().statusCode(201).extract().path("pan");
        Map<String, Object> request = Map.of("amount", 1000, "rrn", TestDataFactory.uniqueRrn());
        given().spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/cards/{pan}/reserve", pan)
                .then()
                .statusCode(200);

        // When
        Response response = given()
                .spec(ApiSpecifications.GATEWAY)
                .body(request)
                .when()
                .post("/api/cards/{pan}/reserve", pan);

        // Then
        response.then()
                .statusCode(409)
                .body("message", notNullValue())
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void shouldExposeGatewayCacheHeaderForRepeatedCardQuery() {
        // Given
        String uniqueIssuer = "ISS001";
        var firstRequest = given()
                .spec(ApiSpecifications.GATEWAY)
                .queryParam("issuerId", uniqueIssuer)
                .queryParam("limit", 3);

        // When
        Response first = firstRequest.when().get("/api/cards");
        Response second = given()
                .spec(ApiSpecifications.GATEWAY)
                .queryParam("issuerId", uniqueIssuer)
                .queryParam("limit", 3)
                .when()
                .get("/api/cards");

        // Then
        first.then().statusCode(200).header("X-Cache", oneOf("MISS", "HIT"));
        second.then().statusCode(200).header("X-Cache", equalTo("HIT"));
    }

    private static Stream<Arguments> invalidCardRequests() {
        return Stream.of(
                Arguments.of("bin", "40000"),
                Arguments.of("cardholderName", "lowercase"),
                Arguments.of("currencyCode", "64"),
                Arguments.of("dailyLimit", -1),
                Arguments.of("monthlyLimit", -1)
        );
    }
}
