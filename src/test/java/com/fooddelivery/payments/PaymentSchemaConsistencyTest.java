package com.fooddelivery.payments;

import com.fooddelivery.common.test.SchemaConsistency;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This service's entities must agree with its migrations: production runs
 * {@code ddl-auto: validate}, so a column the entities disagree with stops it booting.
 *
 * <p>Static rather than database-backed on purpose -- Testcontainers are excluded by project rule
 * and H2 cannot execute the shipped Postgres schema. See {@link SchemaConsistency}.
 */
public class PaymentSchemaConsistencyTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final String BASE_PACKAGE = "com.fooddelivery.payments";

    @Test
    void entitiesAgreeWithTheMigrations() {
        List<String> problems = SchemaConsistency.mismatches(MIGRATIONS, BASE_PACKAGE, Set.of());
        if (!problems.isEmpty()) {
            fail("Entities and the migrations disagree; ddl-auto=validate would refuse to start:\n  "
                    + String.join("\n  ", problems));
        }
    }

    @Test
    void theMigrationsAreActuallyParsed() {
        Map<String, Map<String, String>> schema = SchemaConsistency.parseMigrations(MIGRATIONS);
        assertTrue(schema.size() >= 2,
                "the parser read too few tables to be reading the migrations: " + schema.keySet());
    }
}
