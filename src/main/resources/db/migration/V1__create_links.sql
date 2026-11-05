CREATE TABLE links (
    code VARCHAR(32) NOT NULL PRIMARY KEY,
    url VARCHAR(2048) NOT NULL,
    created BIGINT NOT NULL,
    expires BIGINT NULL,
    disabled BIT NOT NULL DEFAULT 0
);

CREATE TABLE clicks (
    code VARCHAR(32) NOT NULL,
    day VARCHAR(10) NOT NULL,
    click_count BIGINT NOT NULL,
    PRIMARY KEY (code, day),
    CONSTRAINT fk_clicks_links FOREIGN KEY (code) REFERENCES links(code)
);