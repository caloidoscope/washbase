-- Spring Authorization Server's JDBC storage (ADR-001): authorization codes, tokens and consents.
-- Copied from the schemas bundled with spring-security-oauth2-authorization-server 7.1.1
-- (oauth2-authorization-schema.sql, oauth2-authorization-consent-schema.sql), adapted for PostgreSQL as their
-- header instructs: blob -> text, timestamp -> timestamptz. Column names must stay as they are (the library's SQL
-- uses them). Registered clients come from configuration, so there is no oauth2_registered_client table.
-- principal_name holds the user's UUID (the token's sub).

create table oauth2_authorization (
    id                            varchar(100)  not null,
    registered_client_id          varchar(100)  not null,
    principal_name                varchar(200)  not null,
    authorization_grant_type      varchar(100)  not null,
    authorized_scopes             varchar(1000) default null,
    attributes                    text          default null,
    state                         varchar(500)  default null,
    authorization_code_value      text          default null,
    authorization_code_issued_at  timestamptz   default null,
    authorization_code_expires_at timestamptz   default null,
    authorization_code_metadata   text          default null,
    access_token_value            text          default null,
    access_token_issued_at        timestamptz   default null,
    access_token_expires_at       timestamptz   default null,
    access_token_metadata         text          default null,
    access_token_type             varchar(100)  default null,
    access_token_scopes           varchar(1000) default null,
    oidc_id_token_value           text          default null,
    oidc_id_token_issued_at       timestamptz   default null,
    oidc_id_token_expires_at      timestamptz   default null,
    oidc_id_token_metadata        text          default null,
    refresh_token_value           text          default null,
    refresh_token_issued_at       timestamptz   default null,
    refresh_token_expires_at      timestamptz   default null,
    refresh_token_metadata        text          default null,
    user_code_value               text          default null,
    user_code_issued_at           timestamptz   default null,
    user_code_expires_at          timestamptz   default null,
    user_code_metadata            text          default null,
    device_code_value             text          default null,
    device_code_issued_at         timestamptz   default null,
    device_code_expires_at        timestamptz   default null,
    device_code_metadata          text          default null,
    primary key (id)
);

create table oauth2_authorization_consent (
    registered_client_id varchar(100)  not null,
    principal_name       varchar(200)  not null,
    authorities          varchar(1000) not null,
    primary key (registered_client_id, principal_name)
);
