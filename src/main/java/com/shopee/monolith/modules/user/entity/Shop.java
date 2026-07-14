package com.shopee.monolith.modules.user.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.user.model.ShopStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "shops")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Shop extends BaseEntity {

    @Column(name = "owner_id", nullable = false, unique = true)
    private UUID ownerId;

    @Column(name = "address_id")
    private UUID addressId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "rating", precision = 3, scale = 2)
    @lombok.Builder.Default
    private BigDecimal rating = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private ShopStatus status = ShopStatus.ACTIVE;

    @Column(name = "verified", nullable = false)
    @lombok.Builder.Default
    private boolean verified = false;

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void suspend() {
        this.status = ShopStatus.SUSPENDED;
    }

    public void reinstate() {
        this.status = ShopStatus.ACTIVE;
    }

    public void verify() {
        this.verified = true;
    }
}
