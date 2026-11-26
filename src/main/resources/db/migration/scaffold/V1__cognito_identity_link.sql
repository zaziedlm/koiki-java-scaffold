create table scaffold_cognito_identity_link (
    issuer varchar(512) not null,
    subject varchar(255) not null,
    framework_user_id uuid not null,
    primary key (issuer, subject),
    unique (issuer, framework_user_id)
);
