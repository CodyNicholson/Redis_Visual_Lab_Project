CREATE TABLE IF NOT EXISTS products (
    id          INT PRIMARY KEY,
    sku         TEXT           NOT NULL,
    name        TEXT           NOT NULL,
    category    TEXT           NOT NULL,
    price       NUMERIC(10, 2) NOT NULL,
    stock       INT            NOT NULL,
    description TEXT           NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- reviews.product_id is deliberately left unindexed: the catalog query aggregates every
-- review on each call, which models an expensive reporting query worth caching.
CREATE TABLE IF NOT EXISTS reviews (
    id         BIGSERIAL PRIMARY KEY,
    product_id INT         NOT NULL,
    rating     INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
