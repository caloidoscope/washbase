-- CAR-19: sign-in is paused after repeated wrong passwords (ADR-001: "attempts are rate-limited on the login ...").
--
-- Keyed by the sign-in identifier as typed on /login, normalized the same way the user lookup does (lower-cased
-- email, +639XXXXXXXXX mobile, anything else trimmed and lower-cased) and stored only as a SHA-256 hash: the typed
-- value can be someone else's address or even a password typed into the wrong field, and it never needs to be read
-- back. Unknown identifiers get rows exactly like known ones, so a pause never reveals whether an account exists.
-- No foreign key to users, for the same reason. Not keyed by IP address (out of scope for CAR-19).
--
-- Times come from the application's Clock (not now()), so tests can move time.

-- 1. Each wrong password (or any failed sign-in) for an identifier that isn't paused. Counted over a sliding window
--    (washbase.auth.sign-in-limit.window). Removed on a successful sign-in, when a pause starts, and once older
--    than the window (per identifier when a failure is recorded; a deployment-wide cleanup job: CAR-38).
create table sign_in_failure (
    id              bigint       generated always as identity primary key,
    identifier_hash char(64)     not null, -- lowercase hex SHA-256 of the normalized identifier
    failed_at       timestamptz  not null
);

create index sign_in_failure_identifier_idx on sign_in_failure (identifier_hash, failed_at);

-- 2. Identifiers whose sign-in is paused until paused_until (washbase.auth.sign-in-limit.pause after the failure
--    that reached the limit). While paused, every attempt is refused without checking the password and isn't
--    counted, so the pause is never extended. Rows past paused_until are ignored and overwritten by the next pause
--    (deployment-wide cleanup: CAR-38).
create table sign_in_pause (
    identifier_hash char(64)     primary key,
    paused_until    timestamptz  not null
);
