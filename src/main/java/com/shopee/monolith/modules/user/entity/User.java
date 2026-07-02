package com.shopee.monolith.modules.user.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
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

@Entity
@Table(name = "users")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "normalized_email", nullable = false, unique = true, length = 255)
    private String normalizedEmail;

    @Column(name = "password_hash") // Nullable for OAuth2 users
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    @lombok.Builder.Default
    private Role role = Role.BUYER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @lombok.Builder.Default
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    public void promoteToSeller() {
        if (this.role == Role.BUYER) {
            this.role = Role.SELLER;
        }
    }

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    protected void normalizeEmailBeforeSave() {
        if (this.email != null) {
            this.normalizedEmail = this.email.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}

