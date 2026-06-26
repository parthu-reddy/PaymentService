package com.fooddelivery.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer extends BaseEntity {

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    private String email;

    @Column(name = "full_name")
    private String fullName;
}
