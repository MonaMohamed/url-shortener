CREATE TABLE click_events (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(10) NOT NULL,
    clicked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(45),
    user_agent TEXT,
    referer TEXT,
    country VARCHAR(2),
    event_id UUID NOT NULL UNIQUE
);

CREATE INDEX idx_click_short_code ON click_events(short_code);
CREATE INDEX idx_click_clicked_at ON click_events(clicked_at);
CREATE INDEX idx_click_event_id ON click_events(event_id);