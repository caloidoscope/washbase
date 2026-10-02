-- People who sign in (ADR-001). Walk-in clients are not users.
create table users (
    id                   uuid         primary key default gen_random_uuid(),
    name                 varchar(100) not null,
    -- Stored trimmed and lower-cased, so the plain unique constraint is case-insensitive.
    email                varchar(254),
    -- Philippine mobile number, stored normalized as +639XXXXXXXXX (09XXXXXXXXX is accepted on input).
    mobile               varchar(13),
    -- Null until the person sets a password (e.g. counter-created clients, CAR-35).
    password_hash        varchar(255),
    role                 varchar(10)  not null,
    active               boolean      not null default true,
    must_change_password boolean      not null default false,
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now(),

    constraint users_email_key unique (email),
    constraint users_mobile_key unique (mobile),
    constraint users_role_check check (role in ('CLIENT', 'STAFF', 'OWNER', 'ADMIN')),
    constraint users_email_or_mobile_check check (email is not null or mobile is not null),
    constraint users_email_lower_check check (email = lower(btrim(email))),
    constraint users_mobile_format_check check (mobile ~ '^\+639[0-9]{9}$')
);

-- Exactly one Admin per deployment: at most one active ADMIN (inactive ones may remain).
create unique index users_one_active_admin on users (role) where role = 'ADMIN' and active;
