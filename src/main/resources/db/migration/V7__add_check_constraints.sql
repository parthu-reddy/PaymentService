-- V7__add_check_constraints.sql
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_amount CHECK (amount >= 0);
