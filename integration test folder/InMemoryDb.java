package com.bnpp.regliss;

// imports (lines 3-8) were folded in the IDE; reconstructed from usage.
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ActiveProfiles("test-db-h2")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InMemoryDb {
}
