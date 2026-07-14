package com.shopee.monolith.modules.order.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * References the media module by ID only (no cross-module FK), same as
 * moderation.Report#targetId — order module does not depend on media entities.
 */
@Entity
@Table(name = "return_evidence")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnEvidence extends BaseEntity {

    @Column(name = "return_id", nullable = false)
    private UUID returnId;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;
}
