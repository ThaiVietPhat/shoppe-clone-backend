package com.shopee.monolith.modules.moderation.repository;

import com.shopee.monolith.modules.moderation.entity.Report;
import com.shopee.monolith.modules.moderation.model.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    Page<Report> findByStatus(ReportStatus status, Pageable pageable);
}
