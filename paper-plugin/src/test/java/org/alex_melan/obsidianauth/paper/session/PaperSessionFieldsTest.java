package org.alex_melan.obsidianauth.paper.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Constitution / {@code data-model.md} invariant: every non-{@code final} instance field of
 * {@link PaperSession} is read by lockdown listeners on the main / region thread without a
 * lock, so it MUST be {@code volatile} (a {@code java.util.concurrent.atomic} reference is
 * {@code final} and therefore exempt).
 *
 * <p>This reflective check is the {@code pluginsMustHaveDataVolatile} rule from the task
 * list, implemented as a plain JUnit test rather than pulling in a static-analysis
 * dependency.
 */
class PaperSessionFieldsTest {

    @Test
    void everyMutableFieldIsVolatile() {
        for (Field field : PaperSession.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
                continue;
            }
            assertThat(Modifier.isVolatile(mods))
                    .as("PaperSession field '%s' is mutable and read cross-thread —"
                            + " it MUST be declared volatile", field.getName())
                    .isTrue();
        }
    }
}
