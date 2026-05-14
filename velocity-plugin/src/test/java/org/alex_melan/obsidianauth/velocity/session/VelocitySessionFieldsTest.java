package org.alex_melan.obsidianauth.velocity.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Constitution / {@code data-model.md} invariant: Velocity has no main thread, so every
 * non-{@code final} instance field of {@link VelocitySession} is read and written across the
 * proxy's scheduler and netty I/O threads and MUST be {@code volatile}.
 *
 * <p>This reflective check is the {@code pluginsMustHaveDataVolatile} rule from the task
 * list, implemented as a plain JUnit test rather than pulling in a static-analysis
 * dependency.
 */
class VelocitySessionFieldsTest {

    @Test
    void everyMutableFieldIsVolatile() {
        for (Field field : VelocitySession.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
                continue;
            }
            assertThat(Modifier.isVolatile(mods))
                    .as("VelocitySession field '%s' is mutable and read cross-thread —"
                            + " it MUST be declared volatile", field.getName())
                    .isTrue();
        }
    }
}
