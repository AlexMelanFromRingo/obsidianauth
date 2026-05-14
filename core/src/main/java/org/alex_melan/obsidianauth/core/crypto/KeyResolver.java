package org.alex_melan.obsidianauth.core.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;

/**
 * Resolves the AES master key according to FR-019's source precedence:
 * <pre>
 *     KMS > key file > environment variable
 * </pre>
 *
 * <p>For MVP we ship the file and env sources as first-class. KMS is exposed as a
 * pluggable interface ({@link KmsResolver}) — callers wiring KMS in supply their own
 * implementation. When no KMS is configured, the resolver falls through.
 *
 * <p>Key file requirements (FR-019a, POSIX):
 * <ul>
 *   <li>Mode MUST be no more permissive than {@code 0600}.</li>
 *   <li>Owner MUST match the process user.</li>
 *   <li>File contents are either 32 raw bytes or a base64-encoded 32 bytes (auto-detected).</li>
 * </ul>
 *
 * <p>All resolution work runs on the {@link AsyncExecutor} — file I/O and KMS calls may
 * each block beyond the main-thread budget.
 */
public final class KeyResolver {

    /** Pluggable KMS source. Returning empty falls through to the next source. */
    public interface KmsResolver {
        Optional<byte[]> resolve(String reference);
    }

    private static final Set<PosixFilePermission> FORBIDDEN_FILE_PERMS = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private final AsyncExecutor async;
    private final KmsResolver kms;
    private final String kmsReference;
    private final Path keyFilePath;
    private final String envVarName;
    private final int keyVersion;

    public KeyResolver(AsyncExecutor async,
                       KmsResolver kms,
                       String kmsReference,
                       Path keyFilePath,
                       String envVarName,
                       int keyVersion) {
        this.async = async;
        this.kms = kms;
        this.kmsReference = (kmsReference == null) ? "" : kmsReference;
        this.keyFilePath = keyFilePath;
        this.envVarName = (envVarName == null) ? "" : envVarName;
        this.keyVersion = keyVersion;
    }

    /** Resolves the active key on the {@link AsyncExecutor}. */
    public CompletableFuture<KeyMaterial> resolve() {
        return async.submit(this::resolveSync);
    }

    /** Visible for testing — production code MUST NOT call this directly. */
    KeyMaterial resolveSync() {
        // 1. KMS (highest precedence).
        if (kms != null && !kmsReference.isBlank()) {
            Optional<byte[]> kmsResult = kms.resolve(kmsReference);
            if (kmsResult.isPresent()) {
                byte[] raw = kmsResult.get();
                if (raw.length != KeyMaterial.KEY_LENGTH_BYTES) {
                    throw new IllegalStateException(
                            "KMS returned a key of length " + raw.length
                                    + " bytes, expected " + KeyMaterial.KEY_LENGTH_BYTES);
                }
                return new KeyMaterial(keyVersion, raw);
            }
        }
        // 2. Key file.
        if (keyFilePath != null) {
            byte[] fromFile = readKeyFile(keyFilePath);
            if (fromFile != null) {
                return new KeyMaterial(keyVersion, fromFile);
            }
        }
        // 3. Environment variable.
        if (!envVarName.isBlank()) {
            String raw = System.getenv(envVarName);
            if (raw != null && !raw.isBlank()) {
                byte[] decoded = decodeBase64OrRaw(raw.trim().getBytes());
                if (decoded.length != KeyMaterial.KEY_LENGTH_BYTES) {
                    throw new IllegalStateException(
                            "Environment variable " + envVarName + " resolves to "
                                    + decoded.length + " bytes, expected " + KeyMaterial.KEY_LENGTH_BYTES);
                }
                return new KeyMaterial(keyVersion, decoded);
            }
        }
        throw new IllegalStateException(
                "No encryption key source resolved (KMS / file / env all empty). "
                        + "See plugins/ObsidianAuth/config.yml > encryption.*");
    }

    private byte[] readKeyFile(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            // POSIX permission check (skipped on platforms without POSIX attributes).
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                PosixFileAttributes attrs = posix.readAttributes();
                Set<PosixFilePermission> perms = attrs.permissions();
                for (PosixFilePermission forbidden : FORBIDDEN_FILE_PERMS) {
                    if (perms.contains(forbidden)) {
                        throw new IllegalStateException(
                                "Key file " + path + " has permissions " + PosixFilePermissions(perms)
                                        + " — must be no more permissive than 0600");
                    }
                }
                String fileOwner = attrs.owner().getName();
                String processOwner = System.getProperty("user.name");
                if (processOwner != null && !processOwner.equals(fileOwner)) {
                    throw new IllegalStateException(
                            "Key file " + path + " is owned by " + fileOwner
                                    + " but the server process is running as " + processOwner);
                }
            }
            byte[] raw = Files.readAllBytes(path);
            // Trim trailing whitespace (handles trailing \n from `openssl rand`).
            int end = raw.length;
            while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r' || raw[end - 1] == ' ')) {
                end--;
            }
            byte[] trimmed = (end == raw.length) ? raw : Arrays.copyOf(raw, end);
            try {
                return decodeBase64OrRaw(trimmed);
            } finally {
                Arrays.fill(raw, (byte) 0);
                if (trimmed != raw) {
                    Arrays.fill(trimmed, (byte) 0);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read key file " + path, e);
        }
    }

    private static byte[] decodeBase64OrRaw(byte[] bytes) {
        // If it's exactly the expected raw length, treat as raw.
        if (bytes.length == KeyMaterial.KEY_LENGTH_BYTES) {
            return bytes.clone();
        }
        // Otherwise try base64.
        try {
            return Base64.getDecoder().decode(bytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Key material is neither " + KeyMaterial.KEY_LENGTH_BYTES
                            + " raw bytes nor valid base64");
        }
    }

    private static String PosixFilePermissions(Set<PosixFilePermission> p) {
        return java.nio.file.attribute.PosixFilePermissions.toString(p);
    }
}
