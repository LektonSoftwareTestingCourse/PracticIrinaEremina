package com.processing.e2e.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

public final class ApiSpecifications {

    public static final RequestSpecification GATEWAY = specification(
            "gateway.url", "http://localhost:8080");
    public static final RequestSpecification CARD_MANAGEMENT = specification(
            "card.management.url", "http://localhost:8081");
    public static final RequestSpecification SWITCH = specification(
            "switch.url", "http://localhost:8082");
    public static final RequestSpecification AUTHORIZATION = specification(
            "authorization.url", "http://localhost:8083");
    public static final RequestSpecification MERCHANT = specification(
            "merchant.url", "http://localhost:8084");
    public static final RequestSpecification TERMINAL = specification(
            "terminal.url", "http://localhost:8085");
    public static final RequestSpecification LOGGER = specification(
            "logger.url", "http://localhost:8088");
    public static final RequestSpecification BIN_LOOKUP = specification(
            "bin.lookup.url", "http://localhost:8096");
    public static final RequestSpecification NOTIFICATION = specification(
            "notification.url", "http://localhost:8097");

    private ApiSpecifications() {
    }

    private static RequestSpecification specification(String property, String defaultUrl) {
        return new RequestSpecBuilder()
                .setBaseUri(System.getProperty(property, defaultUrl))
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }
}
