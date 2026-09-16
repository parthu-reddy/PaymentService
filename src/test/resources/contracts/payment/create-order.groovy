import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("Should create an order in payment service")
    request {
        method 'POST'
        urlPath('/api/v1/payments/create-order')
        headers {
            contentType(applicationJson())
        }
        body([
            internalOrderId: "123e4567-e89b-12d3-a456-426614174000",
            amountInInr: 50.00,
            paymentMethod: "CARD"
        ])
    }
    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            gatewayOrderId: "PAYMENT_LINK_URL",
            gateway: "RAZORPAY"
        ])
    }
}
