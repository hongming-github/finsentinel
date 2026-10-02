package com.finsentinel.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shared setup for platform integration tests. Every test class uses the same properties and imports,
 * so Spring caches one context (and one set of containers) for the whole run. Scheduled investigation
 * polling is off; tests drive {@code InvestigationWorker.runOnce()} directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "finsentinel.investigation.enabled=false")
@Import({TestcontainersConfig.class, StubCaseInvestigator.class})
public @interface PlatformTest {
}
