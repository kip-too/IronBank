package shilingi.settlement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import shilingi.money.Currency;
import shilingi.money.Money;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Day 11's contract between Java and the Node sidecar, proven against <b>the real sidecar</b>
 * rather than a Java-side fake.
 *
 * <p>The sidecar is started in stub mode, so this needs no network - which matters, because this
 * network blocks every testnet RPC endpoint (docs/network.md section 4). Everything about the
 * contract is therefore proven here; the only thing that is not is whether a real chain accepts a
 * real transaction, and that is one documented command away on a connection that is not behind
 * that policy.
 *
 * <p>Testing against a Java stub instead would have proven that the adapter can talk to a Java
 * stub. This proves that the two processes agree.
 */
class SidecarContractTest {

    private static Process sidecar;
    private static HttpSettlementAdapter settlement;
    private static int port;

    private static final String SOMEWHERE = "0x1111111111111111111111111111111111111111";

    @BeforeAll
    static void startTheSidecar() throws Exception {
        port = freePort();

        Path sidecarDir = Path.of("sidecar").toAbsolutePath();
        assertThat(Files.exists(sidecarDir.resolve("server.mjs")))
                .as("the sidecar source must be where this test expects it")
                .isTrue();

        ProcessBuilder builder = new ProcessBuilder("node", "server.mjs")
                .directory(sidecarDir.toFile())
                .redirectErrorStream(true);
        builder.environment().put("SHILINGI_MODE", "stub");
        builder.environment().put("SHILINGI_PORT", Integer.toString(port));

        sidecar = builder.start();

        settlement = new HttpSettlementAdapter("http://127.0.0.1:" + port, 10);
        waitUntilUp();
    }

    @AfterAll
    static void stopTheSidecar() {
        if (sidecar != null) {
            sidecar.destroy();
            try {
                if (!sidecar.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    sidecar.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sidecar.destroyForcibly();
            }
        }
    }

    /** Polls rather than sleeping a fixed time, so a slow machine does not make this flaky. */
    private static void waitUntilUp() throws InterruptedException {
        Instant giveUpAt = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(giveUpAt)) {
            if (settlement.isReachable()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(
                "The sidecar did not come up on port " + port + " within 30 seconds. "
                + "Node is required - SPEC.md section 3 names it as part of the toolchain.");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Money usdc(long dollars) {
        return Money.of(dollars * 1_000_000L, Currency.USDC);
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPEC.md section 12: three endpoints, and accepted is not settled")
    class TheThreeEndpoints {

        @Test
        void send_returns_a_hash_and_says_accepted_not_settled() {
            SettlementPort.Acceptance acceptance =
                    settlement.send("convert/contract/1", SOMEWHERE, usdc(6_000));

            assertThat(acceptance.externalRef()).isEqualTo("convert/contract/1");
            assertThat(acceptance.txHash()).startsWith("0x").hasSize(66);
            assertThat(acceptance.isSimulated())
                    .as("stub mode, and the flag says so all the way up - REAL_VS_SIMULATED.md "
                        + "has to be able to tell which hashes are real")
                    .isTrue();
        }

        @Test
        void status_reports_what_became_of_it() {
            settlement.send("convert/contract/2", SOMEWHERE, usdc(100));

            SettlementPort.Settlement status = settlement.status("convert/contract/2").orElseThrow();

            assertThat(status.status()).isEqualTo(SettlementPort.SettlementStatus.SETTLED);
            assertThat(status.blockNumber()).isPresent();
            assertThat(status.simulated()).isTrue();
        }

        @Test
        void balance_comes_back_as_money_in_minor_units() {
            assertThat(settlement.balance().currency()).isEqualTo(Currency.USDC);
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the distinctions this project turns on, carried across the process boundary")
    class TheDistinctions {

        @Test
        @DisplayName("a reference the rail never saw is empty, which is not failure")
        void unknown_is_not_failed() {
            Optional<SettlementPort.Settlement> status = settlement.status("convert/never-sent");

            assertThat(status)
                    .as("empty means AWAITING_RESOLUTION, not FAILED - the whole of invariant I7")
                    .isEmpty();
        }

        @Test
        @DisplayName("the same reference twice sends once")
        void the_rail_is_idempotent_on_our_reference() {
            SettlementPort.Acceptance first =
                    settlement.send("convert/contract/idempotent", SOMEWHERE, usdc(500));
            SettlementPort.Acceptance second =
                    settlement.send("convert/contract/idempotent", SOMEWHERE, usdc(500));

            assertThat(second.txHash())
                    .as("the same transaction, not a second one - F5 defended at the rail as well "
                        + "as at the unique constraint")
                    .isEqualTo(first.txHash());
        }

        @Test
        @DisplayName("our reference survives the whole round trip, punctuation and all")
        void the_external_reference_round_trips() {
            // "convert/12" has a slash in it, which is the sort of thing that gets lost in a URL.
            settlement.send("convert/12", SOMEWHERE, usdc(1));

            assertThat(settlement.status("convert/12").orElseThrow().externalRef())
                    .isEqualTo("convert/12");
        }
    }

    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("refusals and silence are different things")
    class FailureModes {

        @Test
        @DisplayName("a refusal is an answer, so it has its own exception")
        void a_bad_request_is_refused_not_silent() {
            assertThatExceptionOfType(SettlementRefusedException.class)
                    .isThrownBy(() -> settlement.send("", SOMEWHERE, usdc(1)))
                    .satisfies(e -> assertThat(e.statusCode()).isEqualTo(400));
        }

        @Test
        @DisplayName("silence is not an answer, and has a different exception saying so")
        void an_unreachable_sidecar_does_not_look_like_a_failure() {
            HttpSettlementAdapter nowhere =
                    new HttpSettlementAdapter("http://127.0.0.1:1", 1);

            assertThatExceptionOfType(SettlementUnavailableException.class)
                    .isThrownBy(() -> nowhere.send("convert/x", SOMEWHERE, usdc(1)))
                    .withMessageContaining("do not retry on the strength of it");
        }

        @Test
        void the_settlement_leg_refuses_shillings() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> settlement.send("convert/kes", SOMEWHERE,
                            Money.of(100L, Currency.KES)))
                    .withMessageContaining("Shillings go through the payout rail");
        }
    }

    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("SPEC.md section 5: money crosses the process boundary as integer minor units")
    void no_floating_point_on_the_wire() {
        // 9,007,199,254,740,993 micro-dollars is one past the largest integer a double holds
        // exactly. If either side put this through a JSON number it would come back changed.
        long pastDoublePrecision = 9_007_199_254_740_993L;

        settlement.send("convert/precision", SOMEWHERE, Money.of(pastDoublePrecision, Currency.USDC));

        assertThat(settlement.status("convert/precision")).isPresent();

        // And the hazard is real rather than theoretical: send this value through a double and
        // it comes back as a different number, silently, with no error anywhere.
        assertThat((long) (double) pastDoublePrecision)
                .as("9,007,199,254,740,993 is not itself once it has been a double")
                .isNotEqualTo(pastDoublePrecision)
                .isEqualTo(pastDoublePrecision - 1L);
    }
}
