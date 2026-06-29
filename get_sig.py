import hmac
import hashlib

payload = """{
  "event": "payment.success",
  "payload": {
    "payment": {
      "entity": {
        "order_id": "vyapar_test_123",
        "status": "captured",
        "amount": 10000
      }
    }
  },
  "customer_mobile": "+919876543210"
}
"""

secret = b"test_vyapar_webhook_secret"
signature = hmac.new(secret, payload.encode('utf-8'), hashlib.sha256).hexdigest()
print(signature)
