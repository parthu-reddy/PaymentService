/**
 * Food Delivery Drop-in Payment UI Widget
 * 
 * Embeds seamlessly into any frontend framework.
 * Dynamically loads required SDKs based on the chosen gateway.
 */
class FoodDeliveryCheckout {
    constructor(config) {
        this.apiBaseUrl = config.apiBaseUrl || '/api/v1/payments';
    }

    /**
     * Initiates the payment flow.
     * @param {string} gateway - 'RAZORPAY', 'CASHFREE', or 'VYAPAR'
     * @param {object} orderData - Cart details
     */
    async initiatePayment(gateway, orderData) {
        // 1. Create Order on Backend
        const response = await fetch(`${this.apiBaseUrl}/create-order?gateway=${gateway}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(orderData)
        });

        if (!response.ok) {
            throw new Error('Failed to create order on backend');
        }

        const backendResponse = await response.text();

        // 2. Delegate to specific gateway handler
        switch (gateway) {
            case 'RAZORPAY':
                await this.handleRazorpay(backendResponse, orderData);
                break;
            case 'CASHFREE':
                await this.handleCashfree(backendResponse);
                break;
            case 'VYAPAR':
                this.handleVyapar(backendResponse);
                break;
            default:
                throw new Error('Unsupported Gateway');
        }
    }

    async handleRazorpay(orderId, orderData) {
        // Dynamically inject Razorpay Checkout JS
        await this.loadScript('https://checkout.razorpay.com/v1/checkout.js');

        const options = {
            "key": "rzp_test_YOUR_KEY", // Should ideally be fetched from backend config
            "amount": (orderData.amountInInr * 100).toString(),
            "currency": "INR",
            "name": "Spicy Bite Kitchen",
            "description": "Order Payment",
            "order_id": orderId,
            "handler": function (response) {
                // Successful Payment (Handled securely via Webhooks on backend)
                console.log('Razorpay Success:', response);
                window.location.href = '/order-success.html';
            },
            "theme": {
                "color": "#FF5A5F"
            }
        };

        const rzp = new window.Razorpay(options);
        rzp.open();
    }

    async handleCashfree(orderId) {
        // Dynamically inject Cashfree SDK
        await this.loadScript('https://sdk.cashfree.com/js/v3/cashfree.js');
        
        const cashfree = Cashfree({
            mode: "sandbox" // Change to 'production' for live
        });

        // Backend should ideally return a Cashfree payment session ID, 
        // assuming orderId here acts as the session id for the demo logic
        cashfree.checkout({
            paymentSessionId: orderId,
            redirectTarget: "_self"
        });
    }

    handleVyapar(intentString) {
        const isMobile = /iPhone|iPad|iPod|Android/i.test(navigator.userAgent);

        if (isMobile) {
            // Trigger app switch to GPay/PhonePe directly
            window.location.href = intentString;
        } else {
            // Desktop: Broadcast the intent string so the UI can render a QR Code
            const event = new CustomEvent('vyaparIntentReady', { detail: intentString });
            document.dispatchEvent(event);
        }
    }

    // Utility to dynamically load third-party scripts
    loadScript(src) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = src;
            script.onload = resolve;
            script.onerror = reject;
            document.head.appendChild(script);
        });
    }
}
