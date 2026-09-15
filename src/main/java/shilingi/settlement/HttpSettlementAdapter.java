package shilingi.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import shilingi.money.Currency;
import shilingi.money.Money;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Talks to the Node sidecar over local HTTP (SPEC.md section 12).
 *
 * <h2>No new dependency</h2>
 * {@link HttpClient} is the JDK's, and Jackson is already on the classpath by way of Spring Boot.
 * SPEC.md section 1 rule 6 makes every library a decision, and three endpoints do not need one.
 *
 * <h2>Money crosses the wire as a string of minor units</h2>
 * JSON numbers are IEEE 754 doubles. SPEC.md section 5 forbids floating point for money
 * <i>anywhere</i> - "not in a calculation, not in a DTO, not in a test fixture, not in JSON" - and
 * a 64-bit count of micro-dollars is past the point where a double is exact. So amounts are sent
 * and read as strings, and the sidecar does the same.
 *
 * <h2>A timeout is not a failure</h2>
 * When the sidecar does not answer in time this throws {@link SettlementUnavailableException},
 * which the caller must not treat as "the payment failed". It means the outcome is unknown, and
 * unknown has its own state. Turning a timeout into a failure here would let a retry happen on an
 * instruction that may already have moved money, which is F5 exactly.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No retries.</b> Deliberately. A retry is a decision the instruction state machine
 *       makes, under rules this class cannot see.</li>
 *   <li><b>No authentication.</b> The sidecar listens on 127.0.0.1 and is not reachable from
 *       anywhere else. That is the whole of its security model, and it is stated rather than
 *       implied.</li>
 *   <li><b>No connection pooling or circuit breaking.</b> One local process, a handful of calls.</li>
 * </ul>
 */
@Component
public class HttpSettlementAdapter implements SettlementPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI baseUri;
    private final Duration timeout;

    public HttpSettlementAdapter(@Value("${shilingi.settlement.base-url}") String baseUrl,
                                 @Value("${shilingi.settlement.timeout-seconds}") long timeoutSeconds) {
        this.baseUri = URI.create(baseUrl);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Acceptance send(String externalRef, String to, Money amount) {
        if (amount.currency() == Currency.KES) {
            throw new IllegalArgumentException(
                    "The settlement leg moves dollars. Shillings go through the payout rail.");
        }

        String body = JSON.createObjectNode()
                .put("externalRef", externalRef)
                .put("to", to)
                // A string, not a number. See the class note.
                .put("amountMinor", Long.toString(amount.minorUnits()))
                .toString();

        JsonNode response = call(HttpRequest.newBuilder()
                .uri(baseUri.resolve("/payments"))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), 200, 202);

        return new Acceptance(
                response.path("externalRef").asText(),
                response.path("txHash").asText(),
                response.path("simulated").asBoolean(false));
    }

    @Override
    public Optional<Settlement> status(String externalRef) {
        String path = "/payments/" + URLEncoder.encode(externalRef, StandardCharsets.UTF_8);

        HttpResponse<String> raw = exchange(HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .GET()
                .build());

        if (raw.statusCode() == 404) {
            // The rail has never heard of this reference. NOT a failure - see the interface note.
            return Optional.empty();
        }

        JsonNode response = parse(raw, 200);

        JsonNode block = response.path("blockNumber");
        return Optional.of(new Settlement(
                response.path("externalRef").asText(),
                SettlementStatus.valueOf(response.path("status").asText()),
                response.path("txHash").asText(),
                block.isNull() || block.isMissingNode()
                        ? Optional.empty() : Optional.of(block.asText()),
                response.path("simulated").asBoolean(false)));
    }

    @Override
    public Money balance() {
        JsonNode response = call(HttpRequest.newBuilder()
                .uri(baseUri.resolve("/balance"))
                .timeout(timeout)
                .GET()
                .build(), 200);

        int decimals = response.path("decimals").asInt();
        if (decimals != Currency.USDC.scale()) {
            // docs/network.md keeps this as an open verification. If the chain ever disagrees with
            // SPEC.md section 5, that is a finding and it must be loud rather than adopted.
            throw new IllegalStateException(
                    "The token reports " + decimals + " decimals but SPEC.md section 5 fixes USDC at "
                    + Currency.USDC.scale() + ". This is a finding, not something to adopt quietly: "
                    + "every stored amount in this system assumes the specification's scale.");
        }

        return Money.of(Long.parseLong(response.path("amountMinor").asText()), Currency.USDC);
    }

    /** Whether the sidecar is up, for the demo screen and for a startup check. */
    public boolean isReachable() {
        try {
            return exchange(HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build()).statusCode() == 200;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    private JsonNode call(HttpRequest request, int... acceptable) {
        return parse(exchange(request), acceptable);
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SettlementUnavailableException(request.uri(), e);
        }
    }

    private static JsonNode parse(HttpResponse<String> response, int... acceptable) {
        boolean ok = false;
        for (int status : acceptable) {
            ok |= response.statusCode() == status;
        }
        if (!ok) {
            throw new SettlementRefusedException(response.statusCode(), response.body());
        }
        try {
            return JSON.readTree(response.body());
        } catch (Exception e) {
            throw new SettlementRefusedException(response.statusCode(),
                    "the sidecar answered with something that is not JSON: " + response.body());
        }
    }
}
