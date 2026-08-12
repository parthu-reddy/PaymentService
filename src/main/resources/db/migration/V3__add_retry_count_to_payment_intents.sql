ALTER TABLE payment_intents ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
