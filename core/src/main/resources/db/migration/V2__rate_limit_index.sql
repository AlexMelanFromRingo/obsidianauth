-- Index for the periodic eviction sweep of expired rate-limit buckets.
CREATE INDEX rate_limit_attempts_window_start_idx
    ON rate_limit_attempts (window_start);
