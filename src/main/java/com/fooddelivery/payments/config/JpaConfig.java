package com.fooddelivery.payments.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = {"com.fooddelivery.payments", "com.fooddelivery.common"})
@EntityScan(basePackages = {"com.fooddelivery.payments", "com.fooddelivery.common"})
public class JpaConfig {
}
