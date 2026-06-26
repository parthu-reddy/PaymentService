-- Add performance indices for background reconciliation and DLQ jobs
CREATE INDEX IF NOT EXISTS idx_payment_intents_status_created_at ON payment_intents(status, created_at);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_status_created_at ON webhook_deliveries(processing_status, created_at);
