ALTER TABLE transactions ADD COLUMN amount_refunded DECIMAL(15,2) NOT NULL DEFAULT 0.00;
ALTER TABLE transactions ADD CONSTRAINT chk_txn_refund_limits CHECK (amount_refunded <= amount);
