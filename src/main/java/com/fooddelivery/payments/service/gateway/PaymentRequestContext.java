package com.fooddelivery.payments.service.gateway;


import java.math.BigDecimal;
import java.util.UUID;

public class PaymentRequestContext {
    private String internalOrderId;
    private BigDecimal amountInInr;
    private String receiptRef;
    private String customerPhone;

    public String getInternalOrderId() {
        return this.internalOrderId;
    }

    public BigDecimal getAmountInInr() {
        return this.amountInInr;
    }

    public String getReceiptRef() {
        return this.receiptRef;
    }

    public String getCustomerPhone() {
        return this.customerPhone;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String internalOrderId;
        private BigDecimal amountInInr;
        private String receiptRef;
        private String customerPhone;

        public Builder internalOrderId(String internalOrderId) {
            this.internalOrderId = internalOrderId;
            return this;
        }

        public Builder amountInInr(BigDecimal amountInInr) {
            this.amountInInr = amountInInr;
            return this;
        }

        public Builder receiptRef(String receiptRef) {
            this.receiptRef = receiptRef;
            return this;
        }

        public Builder customerPhone(String customerPhone) {
            this.customerPhone = customerPhone;
            return this;
        }

        public PaymentRequestContext build() {
            PaymentRequestContext ctx = new PaymentRequestContext();
            ctx.internalOrderId = this.internalOrderId;
            ctx.amountInInr = this.amountInInr;
            ctx.receiptRef = this.receiptRef;
            ctx.customerPhone = this.customerPhone;
            return ctx;
        }
    }

}
