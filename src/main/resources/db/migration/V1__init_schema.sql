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
