package com.shopee.monolith.modules.moderation.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.moderation.model.ReportReasonCategory;
import com.shopee.monolith.modules.moderation.model.ReportStatus;
import com.shopee.monolith.modules.moderation.model.ReportTargetType;
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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reports")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", nullable = false, length = 20)
    private ReportReasonCategory reasonCategory;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public void resolve(UUID adminId, String note, Instant now) {
        this.status = ReportStatus.RESOLVED;
        this.resolutionNote = note;
        this.resolvedBy = adminId;
        this.resolvedAt = now;
    }

    public void reject(UUID adminId, String note, Instant now) {
        this.status = ReportStatus.REJECTED;
        this.resolutionNote = note;
        this.resolvedBy = adminId;
        this.resolvedAt = now;
    }
}
