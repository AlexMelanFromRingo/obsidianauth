-- ObsidianAuth — initial schema.
-- Placeholders are substituted by Flyway per Dialect (see core/storage/Dialect.java).

CREATE TABLE enrollment (
    player_uuid          ${binary16_type}  NOT NULL PRIMARY KEY,
    ciphertext           ${blob_type}      NOT NULL,
    nonce                ${binary12_type}  NOT NULL,
    auth_tag             ${binary16_type}  NOT NULL,
    key_version          ${int_unsigned}   NOT NULL,
    enrolled_at          BIGINT            NOT NULL,
    last_verified_at     BIGINT,
    last_step_consumed   BIGINT,
    created_at           BIGINT            NOT NULL
);

CREATE TABLE audit_head (
    id                   ${tinyint}        NOT NULL PRIMARY KEY,
    seq                  BIGINT            NOT NULL,
    this_hash            ${binary32_type}  NOT NULL,
    file_offset          BIGINT            NOT NULL,
    updated_at           BIGINT            NOT NULL,
    CHECK (id = 1)
);

CREATE TABLE rate_limit_attempts (
    key_type             ${tinyint}        NOT NULL,
    key_value            ${blob_type}      NOT NULL,
    window_start         BIGINT            NOT NULL,
    count                ${int_unsigned}   NOT NULL,
    last_attempt_at      BIGINT            NOT NULL,
    PRIMARY KEY (key_type, key_value, window_start)
);
