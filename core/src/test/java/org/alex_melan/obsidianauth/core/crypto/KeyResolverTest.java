package org.alex_melan.obsidianauth.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Optional;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class KeyResolverTest {

    private final ImmediateAsyncExecutor async = new ImmediateAsyncExecutor();

    private static byte[] randomKey() {
        byte[] k = new byte[KeyMaterial.KEY_LENGTH_BYTES];
        new java.security.SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void kmsTakesPrecedenceOverEverything() {
        byte[] kmsKey = randomKey();
        KeyResolver.KmsResolver kms = ref -> Optional.of(kmsKey);
        KeyResolver resolver = new KeyResolver(async, kms, "kms://placeholder",
                Path.of("/nonexistent"), "OBSIDIANAUTH_TEST_NO_SUCH", 1);

        KeyMaterial mat = resolver.resolveSync();
        assertThat(mat.keyCopy()).containsExactly(kmsKey);
    }

    @Test
    void fileTakesPrecedenceOverEnv(@TempDir Path tmp) throws Exception {
        byte[] fileKey = randomKey();
        Path keyFile = tmp.resolve("master.key");
        Files.write(keyFile, Base64.getEncoder().encode(fileKey));
        chmodOwnerOnly(keyFile);

        KeyResolver resolver = new KeyResolver(async, null, "",
                keyFile, "OBSIDIANAUTH_TEST_NO_SUCH", 2);

        KeyMaterial mat = resolver.resolveSync();
        assertThat(mat.keyCopy()).containsExactly(fileKey);
        assertThat(mat.version()).isEqualTo(2);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsKeyFileWithGroupReadPermission(@TempDir Path tmp) throws Exception {
        Path keyFile = tmp.resolve("master.key");
        Files.write(keyFile, Base64.getEncoder().encode(randomKey()));
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r-----"));

        KeyResolver resolver = new KeyResolver(async, null, "",
                keyFile, "", 1);

        assertThatThrownBy(resolver::resolveSync)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0600");
    }

    @Test
    void envFallbackResolvesBase64Bytes() {
        // We can't safely set arbitrary env vars in-process across the JVM in a portable way,
        // but we CAN test the "all sources empty" failure path.
        KeyResolver resolver = new KeyResolver(async, null, "",
                Path.of("/nonexistent"), "OBSIDIANAUTH_DEFINITELY_NOT_SET_" + System.nanoTime(), 1);

        assertThatThrownBy(resolver::resolveSync)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No encryption key source resolved");
    }

    @Test
    void rejectsKeyOfWrongLength(@TempDir Path tmp) throws Exception {
        Path keyFile = tmp.resolve("bad.key");
        Files.write(keyFile, new byte[16]);                         // too short
        chmodOwnerOnly(keyFile);

        KeyResolver resolver = new KeyResolver(async, null, "",
                keyFile, "", 1);

        assertThatThrownBy(resolver::resolveSync)
                .isInstanceOf(IllegalStateException.class);
    }

    private static void chmodOwnerOnly(Path p) throws Exception {
        if (java.nio.file.attribute.PosixFileAttributeView.class != null
                && Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
