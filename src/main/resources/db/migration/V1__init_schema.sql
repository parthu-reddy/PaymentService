-- Source: V1__init_schema.sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE payment_intents (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    order_id VARCHAR(255) NOT NULL,  
    gateway_name VARCHAR(50) NOT NULL,   
    gateway_order_id VARCHAR(255) UNIQUE NOT NULL,   
    amount DECIMAL(15,2) NOT NULL,  
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',   
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,  
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP  
);

CREATE TABLE transactions (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    payment_intent_id UUID NOT NULL REFERENCES payment_intents(id),  
    gateway_payment_id VARCHAR(255) UNIQUE NOT NULL,   
    amount DECIMAL(15,2) NOT NULL,  
    payment_method VARCHAR(50),   
    payment_network VARCHAR(50),   
    status VARCHAR(50) NOT NULL,  
    error_code VARCHAR(100),  
    error_message TEXT,  
    captured_at TIMESTAMP WITH TIME ZONE,  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE refunds (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    transaction_id UUID NOT NULL REFERENCES transactions(id),  
    gateway_refund_id VARCHAR(255) UNIQUE,  
    amount DECIMAL(15,2) NOT NULL CHECK (amount > 0),  
    reason VARCHAR(255) NOT NULL,  
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE webhook_deliveries (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    gateway_name VARCHAR(50) NOT NULL,  
    event_id VARCHAR(255) UNIQUE NOT NULL,   
    event_type VARCHAR(100) NOT NULL,   
    payload JSONB NOT NULL,  
    processing_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',   
    error_log TEXT,  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);


-- Source: V2__add_refund_tracking.sql
ALTER TABLE payment_intents ADD COLUMN amount_refunded DECIMAL(15,2) NOT NULL DEFAULT 0.00;
ALTER TABLE payment_intents ADD CONSTRAINT chk_refund_limits CHECK (amount_refunded <= amount);


-- Source: V3__add_performance_indices.sql
-- Add performance indices for background reconciliation and DLQ jobs
CREATE INDEX IF NOT EXISTS idx_payment_intents_status_created_at ON payment_intents(status, created_at);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_status_created_at ON webhook_deliveries(processing_status, created_at);


-- Source: V4__add_transaction_refund_tracking.sql
ALTER TABLE transactions ADD COLUMN amount_refunded DECIMAL(15,2) NOT NULL DEFAULT 0.00;
ALTER TABLE transactions ADD CONSTRAINT chk_txn_refund_limits CHECK (amount_refunded <= amount);


-- Source: V5__add_outbox_events.sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'UNPROCESSED',
    processed_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_outbox_status_polling ON outbox_events(status, created_at) WHERE status IN ('UNPROCESSED', 'FAILED');


-- Source: V6__add_version_column.sql
ALTER TABLE payment_intents ADD COLUMN version INTEGER DEFAULT 0;
ALTER TABLE transactions ADD COLUMN version INTEGER DEFAULT 0;
ALTER TABLE refunds ADD COLUMN version INTEGER DEFAULT 0;
ALTER TABLE webhook_deliveries ADD COLUMN version INTEGER DEFAULT 0;


-- Source: V10__add_retry_count_to_outbox.sql
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0;


