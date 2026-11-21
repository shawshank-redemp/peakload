-- Menu data owned by the admission service (schema "admission"). Read by the browse endpoint.
-- price_paise: amounts in paise, as in the order service.
CREATE TABLE restaurants (
    id    UUID  PRIMARY KEY,
    name  TEXT  NOT NULL,
    city  TEXT  NOT NULL
);

CREATE TABLE menu_items (
    id             UUID     PRIMARY KEY,
    restaurant_id  UUID     NOT NULL REFERENCES restaurants (id),
    name           TEXT     NOT NULL,
    price_paise    BIGINT   NOT NULL CHECK (price_paise > 0),
    available      BOOLEAN  NOT NULL DEFAULT true
);

CREATE INDEX menu_items_restaurant_idx ON menu_items (restaurant_id);
