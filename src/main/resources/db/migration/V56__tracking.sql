-- Spec 81 — Tracking: perfis e disparo server-side de eventos.
-- UTM em bot_leads (Spec 75): captura no início da conversa (primeiro valor vence).
ALTER TABLE bot_leads ADD COLUMN utm_source   VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_medium   VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_campaign VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_content  VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_term     VARCHAR(255);

CREATE TABLE tracking_profiles (
    id                              UUID PRIMARY KEY,
    merchant_id                     UUID        NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    name                            VARCHAR(255) NOT NULL,
    status                          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE','INACTIVE')),
    facebook_pixel_id               VARCHAR(255),
    facebook_access_token_encrypted TEXT,
    tiktok_pixel_id                 VARCHAR(255),
    tiktok_access_token_encrypted   TEXT,
    utmify_token_encrypted          TEXT,
    otimizey_token_encrypted        TEXT,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tracking_profile_bots (
    profile_id  UUID NOT NULL REFERENCES tracking_profiles (id) ON DELETE CASCADE,
    bot_id      UUID NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    PRIMARY KEY (profile_id, bot_id)
);

CREATE TABLE event_logs (
    id               BIGSERIAL PRIMARY KEY,
    profile_id       UUID        NOT NULL REFERENCES tracking_profiles (id) ON DELETE CASCADE,
    event_type       VARCHAR(32) NOT NULL,          -- LEAD/INITIATE_CHECKOUT/PURCHASE
    event_id         UUID        NOT NULL,          -- id estável do evento do funil (Spec 75)
    status           VARCHAR(16) NOT NULL CHECK (status IN ('OK','ERROR')),
    response_payload TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, event_id)
);

CREATE INDEX ix_tracking_profile_bots_bot ON tracking_profile_bots (bot_id);
CREATE INDEX ix_event_logs_profile ON event_logs (profile_id, created_at DESC);
