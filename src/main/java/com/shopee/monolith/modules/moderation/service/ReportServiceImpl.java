package com.shopee.monolith.modules.moderation.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.moderation.dto.request.CreateReportRequest;
import com.shopee.monolith.modules.moderation.dto.request.ResolveReportRequest;
import com.shopee.monolith.modules.moderation.dto.response.ReportResponse;
import com.shopee.monolith.modules.moderation.entity.Report;
import com.shopee.monolith.modules.moderation.model.ReportStatus;
import com.shopee.monolith.modules.moderation.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportRepository reportRepository;
    private final Clock clock;

    @Override
    @Transactional
    public ReportResponse createReport(UUID reporterId, CreateReportRequest request) {
        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType(request.targetType())
                .targetId(request.targetId())
                .reasonCategory(request.reasonCategory())
                .description(request.description())
                .build();
        return toResponse(reportRepository.save(report));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ReportResponse> listReports(ReportStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var reportPage = (status != null
                ? reportRepository.findByStatus(status, pageable)
                : reportRepository.findAll(pageable))
                .map(this::toResponse);
        return PagedResponse.from(reportPage);
    }

    @Override
    @Transactional
    public ReportResponse resolveReport(UUID reportId, UUID adminId, ResolveReportRequest request) {
        if (request.outcome() != ReportStatus.RESOLVED && request.outcome() != ReportStatus.REJECTED) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new AppException(ErrorCode.REPORT_NOT_FOUND));
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new AppException(ErrorCode.REPORT_ALREADY_RESOLVED);
        }

        Instant now = Instant.now(clock);
        if (request.outcome() == ReportStatus.REJECTED) {
            report.reject(adminId, request.note(), now);
        } else {
            report.resolve(adminId, request.note(), now);
        }

        return toResponse(reportRepository.save(report));
    }

    private ReportResponse toResponse(Report report) {
        return ReportResponse.builder()
                .id(report.getId())
                .reporterId(report.getReporterId())
                .targetType(report.getTargetType())
                .targetId(report.getTargetId())
                .reasonCategory(report.getReasonCategory())
                .description(report.getDescription())
                .status(report.getStatus())
                .resolutionNote(report.getResolutionNote())
                .resolvedBy(report.getResolvedBy())
                .resolvedAt(report.getResolvedAt())
                .createdAt(report.getCreatedAt())
                .build();
    }
}
