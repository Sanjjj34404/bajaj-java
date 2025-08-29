package com.example.bfhjava;

import com.example.bfhjava.dto.FinalQueryRequest;
import com.example.bfhjava.dto.GenerateWebhookRequest;
import com.example.bfhjava.dto.GenerateWebhookResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class SubmissionService {
  private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

  private final WebClient client = WebClient.create("https://bfhldevapigw.healthrx.co.in");

  @Value("${app.name}") private String name;
  @Value("${app.regNo}") private String regNo;
  @Value("${app.email}") private String email;
  @Value("${app.submit:true}") private boolean submit;

  public void execute() {
    log.info("Starting BFH Qualifier flow for {} ({})", name, regNo);

    // 1) Generate webhook and token
    GenerateWebhookResponse gw = client.post()
        .uri("/hiring/generateWebhook/JAVA")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .bodyValue(new GenerateWebhookRequest(name, regNo, email))
        .retrieve()
        .bodyToMono(GenerateWebhookResponse.class)
        .block();

    if (gw == null || gw.webhook() == null || gw.accessToken() == null) {
      throw new IllegalStateException("Failed to obtain webhook or accessToken");
    }

    log.info("Received webhook: {}", gw.webhook());
    log.info("Received accessToken (len={}): ****", gw.accessToken().length());

    String lastTwoDigits = extractLastTwoDigits(regNo);
    int two = Integer.parseInt(lastTwoDigits);
    boolean odd = (two % 2) == 1;

    String finalQuery;
    if (odd) {
      // Question 1 — Highest salary not on 1st day + name, age, department
      finalQuery = "SELECT " +
          "p.AMOUNT AS SALARY, " +
          "CONCAT(e.FIRST_NAME, ' ', e.LAST_NAME) AS NAME, " +
          "TIMESTAMPDIFF(YEAR, e.DOB, CURDATE()) AS AGE, " +
          "d.DEPARTMENT_NAME " +
          "FROM PAYMENTS p " +
          "JOIN EMPLOYEE e ON p.EMP_ID = e.EMP_ID " +
          "JOIN DEPARTMENT d ON e.DEPARTMENT = d.DEPARTMENT_ID " +
          "WHERE DAY(p.PAYMENT_TIME) <> 1 " +
          "ORDER BY p.AMOUNT DESC " +
          "LIMIT 1;";
    } 

    log.info("Prepared final SQL query:\n{}", finalQuery);

    if (!submit) {
      log.warn("Dry run enabled (app.submit=false). Skipping submission.");
      return;
    }

    // 3) Submit answer to webhook URL using JWT token in Authorization header
    // The PDF shows Authorization: <accessToken>. If 401 occurs, try prefixing with 'Bearer '.
    String authHeader = gw.accessToken();

    ClientResponse resp = client.post()
        .uri("/hiring/testWebhook/JAVA")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", authHeader)
        .bodyValue(new FinalQueryRequest(finalQuery))
        .exchangeToMono(Mono::just)
        .block();

    if (resp == null) {
      throw new IllegalStateException("No response from submission endpoint");
    }

    int status = resp.statusCode().value();
    String body = resp.bodyToMono(String.class).blockOptional().orElse("");

    if (status == 200 || status == 201) {
      log.info("Submission successful: {}", body);
    } else if (status == 401) {
      log.warn("401 Unauthorized with raw token. Retrying with 'Bearer ' prefix...");
      ClientResponse retry = client.post()
          .uri("/hiring/testWebhook/JAVA")
          .contentType(MediaType.APPLICATION_JSON)
          .header("Authorization", "Bearer " + gw.accessToken())
          .bodyValue(new FinalQueryRequest(finalQuery))
          .exchangeToMono(Mono::just)
          .block();

      int rs = retry.statusCode().value();
      String rb = retry.bodyToMono(String.class).blockOptional().orElse("");
      if (rs == 200 || rs == 201) {
        log.info("Submission successful (with Bearer): {}", rb);
      } else {
        throw new IllegalStateException("Submission failed (" + rs + "): " + rb);
      }
    } else {
      throw new IllegalStateException("Submission failed (" + status + "): " + body);
    }
  }

  private static String extractLastTwoDigits(String regNo) {
    String digits = regNo.replaceAll("\\D", "");
    if (digits.length() < 2) {
      throw new IllegalArgumentException("regNo must contain at least two digits");
    }
    return digits.substring(digits.length() - 2);
  }
}