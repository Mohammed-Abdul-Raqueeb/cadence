package dev.raqueeb.cadence.harness;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a no-argument method as a test case.
 *
 * <p>JUnit would be the obvious choice and is not used here: it would be the
 * project's only dependency, and the whole point of Cadence is that it has
 * none. The harness this annotation belongs to is about two hundred lines,
 * which is a fair price for keeping the dependency count at zero.
 *
 * <p>What is given up: parameterised tests, fixtures, tagging, parallel
 * execution and IDE integration. For a suite of this size, none of those has
 * been missed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {

    /**
     * A sentence describing what the case proves.
     *
     * <p>Shown instead of the method name when present, so a failure reads as
     * a statement about the system rather than as an identifier.
     *
     * @return the description, or an empty string to use the method name
     */
    String value() default "";
}
