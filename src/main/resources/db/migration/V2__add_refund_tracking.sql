ALTER TABLE payment_intents ADD COLUMN amount_refunded DECIMAL(15,2) NOT NULL DEFAULT 0.00;
ALTER TABLE payment_intents ADD CONSTRAINT chk_refund_limits CHECK (amount_refunded <= amount);
