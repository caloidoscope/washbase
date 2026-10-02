-- CAR-18: people stay signed in on the web app until they sign out (ADR-001: refresh tokens rotated on every use).
--
-- 1. Refresh-token reuse detection. Spring Authorization Server replaces the refresh token in oauth2_authorization
--    on every use (reuseRefreshTokens=false) and forgets the old one. To recognise a replaced token presented again
--    (a stolen copy, or a replayed request), every replaced refresh token is remembered here, as a SHA-256 hash only
--    (never the token itself), until it would have expired anyway. Presenting one removes the whole authorization:
--    the person is signed out everywhere that authorization was used and must sign in again.
--    Rows go away with their authorization (on delete cascade) or once expired (cleanup job: CAR-38 follow-up).
create table oauth2_replaced_refresh_token (
    token_hash       char(64)     not null, -- lowercase hex SHA-256 of the replaced refresh token value
    authorization_id varchar(100) not null references oauth2_authorization (id) on delete cascade,
    expires_at       timestamptz  not null, -- the replaced token's own expiry; after it, "expired" is enough
    primary key (token_hash)
);

create index oauth2_replaced_refresh_token_authorization_idx on oauth2_replaced_refresh_token (authorization_id);

-- 2. Lookups by token value. JdbcOAuth2AuthorizationService finds authorizations with "<column> = ?". The access
--    token is now looked up on every /api/** request (a revoked or replaced access token is refused at once), and
--    the refresh token on every renewal. Hash indexes: equality only, and no size limit on the indexed value (a JWT
--    is about 1 KB).
create index oauth2_authorization_access_token_value_idx on oauth2_authorization using hash (access_token_value);
create index oauth2_authorization_refresh_token_value_idx on oauth2_authorization using hash (refresh_token_value);
