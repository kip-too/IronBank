package shilingi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * <p>This is an open-source reference implementation of accounting and orchestration logic.
 * It does not convert currency, hold customer money, or move real value. See PROBLEM.md
 * section 7 - that boundary is a design constraint, not a disclaimer.
 */
@SpringBootApplication
public class ShilingiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShilingiApplication.class, args);
    }
}
