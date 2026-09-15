package shilingi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import shilingi.clock.ClockPort;
import shilingi.clock.SystemClockAdapter;

import java.time.ZoneId;

/**
 * Supplies the single {@link ClockPort} the application runs on.
 *
 * <p>The zone comes from the required property {@code shilingi.clock.zone} and has no default
 * in this file. That is deliberate: which zone a business date is decided in determines which
 * month a posting falls into, so it is a financial decision. If the property is missing the
 * application refuses to start, which is the correct outcome - a clock with a guessed zone
 * would silently misdate every month-end.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li>No demo or scripted-clock profile yet. The demo (day 12) will need a bean definition
 *       that supplies {@code MutableClock} instead of this one.</li>
 * </ul>
 */
@Configuration
public class ClockConfig {

    @Bean
    public ClockPort clockPort(@Value("${shilingi.clock.zone}") String zoneId) {
        return new SystemClockAdapter(ZoneId.of(zoneId));
    }
}
