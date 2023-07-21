package org.example;

import io.avaje.http.client.HttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;

class TestControllerTest {

  private static TestPair pair = new TestPair();
  private static HttpClient client = pair.client();

  @AfterAll
  static void end() {
    pair.stop();
  }

  @Test
  void hello() {
    HttpResponse<String> res = client.request()
      .GET()
      .asString();

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).isEqualTo("Hello world - index");
  }

  @Test
  void strBody() {
    HttpResponse<String> res = client.request()
      .path("test/strBody")
      .body("{\"key\":42}")
      .POST()
      .asString();

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).isEqualTo("{\"key\":42}");
    assertThat(res.headers().firstValue("Content-Type")).isPresent().get().isEqualTo("application/json");
    assertThat(res.headers().firstValue("Content-Length")).isPresent();
  }
}
