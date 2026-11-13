package com.checkout.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentGatewayEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final AtomicInteger ACQUIRER_CALLS = new AtomicInteger();
  private static final AtomicReference<JsonNode> ACQUIRER_REQUEST = new AtomicReference<>();
  private static final AtomicReference<String> ACQUIRER_REQUEST_ID = new AtomicReference<>();
  private static final HttpServer ACQUIRER = startAcquirer();

  @Autowired
  TestRestTemplate http;

  @DynamicPropertySource
  static void acquirerUrl(DynamicPropertyRegistry properties) {
    properties.add("acquirer.url",
        () -> "http://127.0.0.1:" + ACQUIRER.getAddress().getPort() + "/payments");
  }

  private static HttpServer startAcquirer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/payments", exchange -> {
        try (exchange) {
          ACQUIRER_CALLS.incrementAndGet();
          ACQUIRER_REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
          JsonNode request = JSON.readTree(exchange.getRequestBody());
          ACQUIRER_REQUEST.set(request);
          String card = request.path("card_number").asText();
          boolean unavailable = card.endsWith("0");
          boolean authorized = card.endsWith("1");
          byte[] response = (unavailable ? "{}" : """
              {"authorized": %s, "authorization_code": "12345678-1234-1234-1234-123456789012"}
              """.formatted(authorized)).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(unavailable ? 503 : 200, response.length);
          exchange.getResponseBody().write(response);
        }
      });
      server.start();
      return server;
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot start test acquirer", ex);
    }
  }

  @BeforeEach
  void resetAcquirer() {
    ACQUIRER_CALLS.set(0);
    ACQUIRER_REQUEST.set(null);
    ACQUIRER_REQUEST_ID.set(null);
  }

  @AfterAll
  static void stopAcquirer() {
    ACQUIRER.stop(0);
  }

  @ParameterizedTest
  @CsvSource({"12345678900001, Authorized", "12345678900002, Declined"})
  void createsAndRetrievesPaymentOverHttp(String card, String status) throws Exception {
    ResponseEntity<String> created = pay(card);

    assertThat(created.getStatusCode().value()).isEqualTo(201);
    JsonNode payment = JSON.readTree(created.getBody());
    assertThat(payment.path("status").asText()).isEqualTo(status);
    assertThat(payment.path("card_number_last_four").asText())
        .isEqualTo(card.substring(card.length() - 4));
    assertThat(payment.has("card_number")).isFalse();
    assertThat(payment.has("cvv")).isFalse();
    String requestId = created.getHeaders().getFirst("X-Request-ID");
    assertThat(requestId).isNotBlank();
    assertThat(created.getHeaders().getLocation()).isNotNull();
    assertThat(created.getHeaders().getLocation().toString())
        .isEqualTo("/payments/" + payment.path("id").asText());

    ResponseEntity<String> retrieved = http.getForEntity(
        created.getHeaders().getLocation(), String.class);
    assertThat(retrieved.getStatusCode().value()).isEqualTo(200);
    assertThat(JSON.readTree(retrieved.getBody())).isEqualTo(payment);
    assertThat(ACQUIRER_CALLS.get()).isEqualTo(1);
    assertThat(ACQUIRER_REQUEST_ID.get()).isEqualTo(requestId);
    assertThat(ACQUIRER_REQUEST.get()).isEqualTo(JSON.readTree("""
        {"card_number":"%s","expiry_date":"12/2099","currency":"GBP","amount":10,"cvv":"123"}
        """.formatted(card)));
  }

  @Test
  void rejectsInvalidPaymentBeforeCallingAcquirer() throws Exception {
    ResponseEntity<String> response = pay("invalid");

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(JSON.readTree(response.getBody()).path("status").asText()).isEqualTo("Rejected");
    assertThat(ACQUIRER_CALLS.get()).isZero();
  }

  @Test
  void propagatesAcquirerUnavailabilityWithoutRetry() throws Exception {
    ResponseEntity<String> response = pay("12345678900000");

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getLocation()).isNull();
    assertThat(JSON.readTree(response.getBody()).has("status")).isFalse();
    assertThat(ACQUIRER_CALLS.get()).isEqualTo(1);
  }

  private ResponseEntity<String> pay(String card) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Request-ID", "e2e-request");
    String body = """
        {"card_number":"%s","expiry_month":12,"expiry_year":2099,
         "currency":"GBP","amount":10,"cvv":"123"}
        """.formatted(card);
    return http.postForEntity("/payments", new HttpEntity<>(body, headers), String.class);
  }
}
