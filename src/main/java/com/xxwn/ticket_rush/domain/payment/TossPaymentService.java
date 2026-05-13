package com.xxwn.ticket_rush.domain.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Service
@Slf4j
public class TossPaymentService {

    @Value("${toss.secret-key}")
    private String secretKey;

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.tosspayments.com")
            .build();

    public Map<String, Object> confirm(String paymentKey, String orderId, int amount) {
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        return restClient.post()
                .uri("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, res) -> {
                            throw new TossPaymentException(new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
                        })
                .body(new ParameterizedTypeReference<>() {});
    }
}
