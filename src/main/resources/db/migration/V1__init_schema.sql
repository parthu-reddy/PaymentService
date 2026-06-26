CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE merchants (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    name VARCHAR(255) NOT NULL,  
    email VARCHAR(255) UNIQUE NOT NULL,  
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',  
    settlement_account_id VARCHAR(255),  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,  
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP  
);

CREATE TABLE customers (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    phone_number VARCHAR(15) UNIQUE NOT NULL,  
    email VARCHAR(255),  
    full_name VARCHAR(255),  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP  
);

CREATE TABLE orders (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    customer_id UUID NOT NULL REFERENCES customers(id),  
    merchant_id UUID NOT NULL REFERENCES merchants(id),  
    total_amount DECIMAL(15,2) NOT NULL CHECK (total_amount > 0),  
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',  
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',   
    receipt_reference VARCHAR(255),  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,  
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP  
);

CREATE TABLE payment_intents (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    order_id UUID NOT NULL REFERENCES orders(id),  
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
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP  
);

CREATE TABLE refunds (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    transaction_id UUID NOT NULL REFERENCES transactions(id),  
    gateway_refund_id VARCHAR(255) UNIQUE,  
    amount DECIMAL(15,2) NOT NULL CHECK (amount > 0),  
    reason VARCHAR(255) NOT NULL,  
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP  
);

CREATE TABLE webhook_deliveries (  
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  
    gateway_name VARCHAR(50) NOT NULL,  
    event_id VARCHAR(255) UNIQUE NOT NULL,   
    event_type VARCHAR(100) NOT NULL,   
    payload JSONB NOT NULL,  
    processing_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',   
    error_log TEXT,  
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP  
);
