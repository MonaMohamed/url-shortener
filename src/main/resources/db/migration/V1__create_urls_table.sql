CREATE TABLE shortened_urls (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(10) NOT NULL UNIQUE,
    original_url TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE,
    click_count BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255)
);

CREATE INDEX idx_short_code ON shortened_urls(short_code);
CREATE INDEX idx_expires_at ON shortened_urls(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_active ON shortened_urls(active);
