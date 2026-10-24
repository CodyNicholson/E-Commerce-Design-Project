-- MRO Mirror schema. Each bounded context owns its own Postgres schema so the
-- production service boundaries (PIM, Accounts, Pricing, OMS, Procurement,
-- KeepStock) stay visible even though one binary serves them all.

CREATE SCHEMA IF NOT EXISTS pim;
CREATE SCHEMA IF NOT EXISTS accounts;
CREATE SCHEMA IF NOT EXISTS pricing;
CREATE SCHEMA IF NOT EXISTS oms;
CREATE SCHEMA IF NOT EXISTS procurement;
CREATE SCHEMA IF NOT EXISTS keepstock;

-- ---------------------------------------------------------------- PIM
-- attribute_schema is the leaf category's technical schema:
-- [{"key":"hp","label":"Horsepower","type":"number|enum|text|bool","unit":"HP","facet":true}]
CREATE TABLE pim.categories (
    id               TEXT PRIMARY KEY,
    parent_id        TEXT REFERENCES pim.categories(id),
    name             TEXT NOT NULL,
    unspsc           TEXT,
    attribute_schema JSONB NOT NULL DEFAULT '[]'
);

CREATE TABLE pim.products (
    sku              TEXT PRIMARY KEY,
    category_id      TEXT NOT NULL REFERENCES pim.categories(id),
    name             TEXT NOT NULL,
    brand            TEXT NOT NULL,
    mfr_part_number  TEXT NOT NULL,
    description      TEXT NOT NULL DEFAULT '',
    list_price       NUMERIC(12,2) NOT NULL,
    uom              TEXT NOT NULL DEFAULT 'EA',
    weight_lb        NUMERIC(8,2) NOT NULL DEFAULT 1,
    attributes       JSONB NOT NULL DEFAULT '{}',
    hazmat_un        TEXT,
    hazmat_class     TEXT,
    sds_url          TEXT,
    drop_ship_only   BOOLEAN NOT NULL DEFAULT FALSE,
    status           TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','discontinued')),
    replacement_sku  TEXT REFERENCES pim.products(sku)
);
CREATE INDEX products_category_idx ON pim.products(category_id);
CREATE INDEX products_attributes_idx ON pim.products USING GIN (attributes);

-- Competitor, OEM, obsolete and UPC numbers that resolve to one of our SKUs.
-- ref_norm is the part number upper-cased with punctuation stripped.
CREATE TABLE pim.cross_references (
    id              SERIAL PRIMARY KEY,
    ref_type        TEXT NOT NULL CHECK (ref_type IN ('competitor','oem','obsolete','upc')),
    ref_brand       TEXT NOT NULL,
    ref_part_number TEXT NOT NULL,
    ref_norm        TEXT NOT NULL,
    sku             TEXT NOT NULL REFERENCES pim.products(sku),
    note            TEXT NOT NULL DEFAULT ''
);
CREATE INDEX cross_references_norm_idx ON pim.cross_references(ref_norm);

-- ---------------------------------------------------------------- Accounts
-- Corporation -> Division -> Facility -> Cost Center
CREATE TABLE accounts.org_units (
    id         TEXT PRIMARY KEY,
    parent_id  TEXT REFERENCES accounts.org_units(id),
    kind       TEXT NOT NULL CHECK (kind IN ('corporation','division','facility','cost_center')),
    name       TEXT NOT NULL,
    code       TEXT NOT NULL,
    state      TEXT,
    city       TEXT,
    lat        DOUBLE PRECISION,
    lon        DOUBLE PRECISION
);

CREATE TABLE accounts.users (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    email           TEXT NOT NULL UNIQUE,
    title           TEXT NOT NULL,
    role            TEXT NOT NULL CHECK (role IN ('buyer','approver','technician','admin')),
    corporation_id  TEXT NOT NULL REFERENCES accounts.org_units(id),
    facility_id     TEXT REFERENCES accounts.org_units(id),
    cost_center_id  TEXT REFERENCES accounts.org_units(id),
    per_order_limit NUMERIC(12,2) NOT NULL,
    monthly_limit   NUMERIC(12,2) NOT NULL,
    approver_id     TEXT REFERENCES accounts.users(id),
    certifications  TEXT[] NOT NULL DEFAULT '{}',
    badge_id        TEXT UNIQUE
);

CREATE TABLE accounts.blanket_pos (
    id               TEXT PRIMARY KEY,
    corporation_id   TEXT NOT NULL REFERENCES accounts.org_units(id),
    facility_id      TEXT REFERENCES accounts.org_units(id),
    po_number        TEXT NOT NULL,
    amount_authorized NUMERIC(12,2) NOT NULL,
    amount_consumed  NUMERIC(12,2) NOT NULL DEFAULT 0,
    valid_from       DATE NOT NULL,
    valid_to         DATE NOT NULL
);

-- Corporate policy: buying anything in category (or its descendants) needs a certification.
CREATE TABLE accounts.category_restrictions (
    corporation_id TEXT NOT NULL REFERENCES accounts.org_units(id),
    category_id    TEXT NOT NULL REFERENCES pim.categories(id),
    required_cert  TEXT NOT NULL,
    PRIMARY KEY (corporation_id, category_id)
);

CREATE TABLE accounts.tax_exemptions (
    id                 SERIAL PRIMARY KEY,
    corporation_id     TEXT NOT NULL REFERENCES accounts.org_units(id),
    state              TEXT NOT NULL,
    certificate_number TEXT NOT NULL,
    exemption_type     TEXT NOT NULL,
    expires_on         DATE NOT NULL
);

-- ---------------------------------------------------------------- Pricing
CREATE TABLE pricing.contract_prices (
    corporation_id TEXT NOT NULL REFERENCES accounts.org_units(id),
    sku            TEXT NOT NULL REFERENCES pim.products(sku),
    price          NUMERIC(12,2) NOT NULL,
    contract_ref   TEXT NOT NULL,
    valid_to       DATE NOT NULL,
    PRIMARY KEY (corporation_id, sku)
);

CREATE TABLE pricing.category_discounts (
    corporation_id TEXT NOT NULL REFERENCES accounts.org_units(id),
    category_id    TEXT NOT NULL REFERENCES pim.categories(id),
    discount_pct   NUMERIC(5,2) NOT NULL,
    PRIMARY KEY (corporation_id, category_id)
);

-- Volume breaks apply to every customer; keyed by category so a whole family shares them.
CREATE TABLE pricing.volume_tiers (
    category_id  TEXT NOT NULL REFERENCES pim.categories(id),
    min_qty      INT NOT NULL,
    discount_pct NUMERIC(5,2) NOT NULL,
    PRIMARY KEY (category_id, min_qty)
);

CREATE TABLE pricing.tax_rates (
    state TEXT PRIMARY KEY,
    rate  NUMERIC(6,4) NOT NULL
);

-- ---------------------------------------------------------------- OMS
CREATE TABLE oms.nodes (
    id             TEXT PRIMARY KEY,
    kind           TEXT NOT NULL CHECK (kind IN ('dc','branch','supplier')),
    name           TEXT NOT NULL,
    city           TEXT NOT NULL,
    state          TEXT NOT NULL,
    lat            DOUBLE PRECISION NOT NULL,
    lon            DOUBLE PRECISION NOT NULL,
    hazmat_capable BOOLEAN NOT NULL DEFAULT FALSE,
    will_call      BOOLEAN NOT NULL DEFAULT FALSE,
    lead_days      INT NOT NULL DEFAULT 0
);

CREATE TABLE oms.inventory (
    node_id  TEXT NOT NULL REFERENCES oms.nodes(id),
    sku      TEXT NOT NULL REFERENCES pim.products(sku),
    on_hand  INT NOT NULL CHECK (on_hand >= 0),
    reserved INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    PRIMARY KEY (node_id, sku)
);

CREATE SEQUENCE oms.order_seq START 100200;

CREATE TABLE oms.orders (
    id               TEXT PRIMARY KEY,
    user_id          TEXT NOT NULL REFERENCES accounts.users(id),
    corporation_id   TEXT NOT NULL REFERENCES accounts.org_units(id),
    facility_id      TEXT NOT NULL REFERENCES accounts.org_units(id),
    cost_center_id   TEXT NOT NULL REFERENCES accounts.org_units(id),
    po_number        TEXT NOT NULL DEFAULT '',
    blanket_po_id    TEXT REFERENCES accounts.blanket_pos(id),
    source           TEXT NOT NULL CHECK (source IN ('web','punchout','edi','keepstock')),
    status           TEXT NOT NULL CHECK (status IN ('pending_approval','released','shipped','rejected')),
    delivery_method  TEXT NOT NULL CHECK (delivery_method IN ('ship','will_call')),
    fulfillment_pref TEXT NOT NULL CHECK (fulfillment_pref IN ('ship_available','ship_complete')),
    subtotal         NUMERIC(12,2) NOT NULL,
    tax              NUMERIC(12,2) NOT NULL,
    total            NUMERIC(12,2) NOT NULL,
    tax_note         TEXT NOT NULL DEFAULT '',
    approver_id      TEXT REFERENCES accounts.users(id),
    governance       JSONB NOT NULL DEFAULT '[]',
    approvals        JSONB NOT NULL DEFAULT '[]',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX orders_user_idx ON oms.orders(user_id, created_at);

CREATE TABLE oms.order_lines (
    order_id     TEXT NOT NULL REFERENCES oms.orders(id) ON DELETE CASCADE,
    line_no      INT NOT NULL,
    sku          TEXT NOT NULL REFERENCES pim.products(sku),
    qty          INT NOT NULL CHECK (qty > 0),
    unit_price   NUMERIC(12,2) NOT NULL,
    price_source TEXT NOT NULL,
    extended     NUMERIC(12,2) NOT NULL,
    -- KeepStock replenishment lines point back at the bin or vending slot they refill.
    ref          TEXT,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE oms.shipments (
    id          SERIAL PRIMARY KEY,
    order_id    TEXT NOT NULL REFERENCES oms.orders(id) ON DELETE CASCADE,
    node_id     TEXT REFERENCES oms.nodes(id),
    method      TEXT NOT NULL,
    eta_days    INT NOT NULL,
    eta_label   TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'released',
    note        TEXT NOT NULL DEFAULT ''
);

CREATE TABLE oms.shipment_lines (
    shipment_id INT NOT NULL REFERENCES oms.shipments(id) ON DELETE CASCADE,
    sku         TEXT NOT NULL REFERENCES pim.products(sku),
    qty         INT NOT NULL
);

-- ---------------------------------------------------------------- Procurement (PunchOut / EDI)
CREATE TABLE procurement.punchout_identities (
    identity        TEXT PRIMARY KEY,
    shared_secret   TEXT NOT NULL,
    erp_name        TEXT NOT NULL,
    corporation_id  TEXT NOT NULL REFERENCES accounts.org_units(id),
    default_user_id TEXT NOT NULL REFERENCES accounts.users(id)
);

CREATE TABLE procurement.punchout_sessions (
    id             TEXT PRIMARY KEY,
    identity       TEXT NOT NULL REFERENCES procurement.punchout_identities(identity),
    user_id        TEXT NOT NULL REFERENCES accounts.users(id),
    buyer_cookie   TEXT NOT NULL,
    return_url     TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','returned')),
    order_message  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE procurement.edi_partners (
    isa_id          TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    corporation_id  TEXT NOT NULL REFERENCES accounts.org_units(id),
    default_user_id TEXT NOT NULL REFERENCES accounts.users(id)
);

CREATE TABLE procurement.edi_messages (
    id         SERIAL PRIMARY KEY,
    direction  TEXT NOT NULL CHECK (direction IN ('inbound','outbound')),
    doc_type   TEXT NOT NULL,
    partner    TEXT NOT NULL,
    order_id   TEXT REFERENCES oms.orders(id) ON DELETE SET NULL,
    body       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- KeepStock (VMI)
CREATE TABLE keepstock.bins (
    id              TEXT PRIMARY KEY,
    facility_id     TEXT NOT NULL REFERENCES accounts.org_units(id),
    cost_center_id  TEXT NOT NULL REFERENCES accounts.org_units(id),
    sku             TEXT NOT NULL REFERENCES pim.products(sku),
    location        TEXT NOT NULL,
    min_qty         INT NOT NULL,
    max_qty         INT NOT NULL,
    on_hand         INT NOT NULL,
    on_order        INT NOT NULL DEFAULT 0,
    last_scanned_at TIMESTAMPTZ
);

CREATE TABLE keepstock.vending_machines (
    id             TEXT PRIMARY KEY,
    facility_id    TEXT NOT NULL REFERENCES accounts.org_units(id),
    cost_center_id TEXT NOT NULL REFERENCES accounts.org_units(id),
    name           TEXT NOT NULL,
    location       TEXT NOT NULL
);

CREATE TABLE keepstock.vending_slots (
    machine_id  TEXT NOT NULL REFERENCES keepstock.vending_machines(id),
    slot        TEXT NOT NULL,
    sku         TEXT NOT NULL REFERENCES pim.products(sku),
    on_hand     INT NOT NULL,
    capacity    INT NOT NULL,
    par         INT NOT NULL,
    on_order    INT NOT NULL DEFAULT 0,
    daily_limit INT NOT NULL,
    PRIMARY KEY (machine_id, slot)
);

CREATE TABLE keepstock.vending_events (
    id         SERIAL PRIMARY KEY,
    machine_id TEXT NOT NULL REFERENCES keepstock.vending_machines(id),
    slot       TEXT NOT NULL,
    badge_id   TEXT NOT NULL,
    user_id    TEXT REFERENCES accounts.users(id),
    sku        TEXT NOT NULL,
    qty        INT NOT NULL,
    outcome    TEXT NOT NULL,
    message    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
