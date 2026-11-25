CREATE TABLE platform_agreement (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    doc varchar(32) NOT NULL CHECK (doc IN ('PRIVACY_POLICY','USER_AGREEMENT',
        'CHILD_PRIVACY_RULES','TRADE_AGREEMENT','ENROLLMENT_NOTICE')),
    version varchar(40) NOT NULL CHECK (btrim(version) <> ''),
    title varchar(160) NOT NULL CHECK (btrim(title) <> ''),
    published_on date,
    UNIQUE (doc, version)
);

CREATE UNIQUE INDEX idx_platform_agreement_publication ON platform_agreement (doc, published_on)
    WHERE published_on IS NOT NULL;

CREATE TABLE platform_agreement_section (
    agreement_id uuid NOT NULL REFERENCES platform_agreement(id),
    position integer NOT NULL CHECK (position > 0),
    title varchar(200) NOT NULL CHECK (btrim(title) <> ''),
    body text NOT NULL CHECK (btrim(body) <> ''),
    PRIMARY KEY (agreement_id, position)
);
