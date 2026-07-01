with open("src/main/java/com/fooddelivery/payments/repository/IPaymentIntentRepository.java", "r") as f:
    content = f.read()

import_str = "import java.util.Optional;\nimport org.springframework.data.jpa.repository.Lock;\nimport jakarta.persistence.LockModeType;\nimport org.springframework.data.jpa.repository.Query;\nimport org.springframework.data.repository.query.Param;"
content = content.replace("import java.util.Optional;", import_str)

method_str = """    Optional<PaymentIntent> findByGatewayOrderId(String gatewayOrderId);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentIntent p WHERE p.gatewayOrderId = :gatewayOrderId")
    Optional<PaymentIntent> findLockedByGatewayOrderId(@Param("gatewayOrderId") String gatewayOrderId);"""
content = content.replace("    Optional<PaymentIntent> findByGatewayOrderId(String gatewayOrderId);", method_str)

with open("src/main/java/com/fooddelivery/payments/repository/IPaymentIntentRepository.java", "w") as f:
    f.write(content)
