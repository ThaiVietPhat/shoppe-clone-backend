package com.shopee.monolith.modules.moderation.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.moderation.dto.request.CreateReportRequest;
import com.shopee.monolith.modules.moderation.dto.request.ResolveReportRequest;
import com.shopee.monolith.modules.moderation.dto.response.ReportResponse;
import com.shopee.monolith.modules.moderation.entity.Report;
import com.shopee.monolith.modules.moderation.model.ReportReasonCategory;
import com.shopee.monolith.modules.moderation.model.ReportStatus;
import com.shopee.monolith.modules.moderation.model.ReportTargetType;
import com.shopee.monolith.modules.moderation.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock
    private ReportRepository reportRepository;

    private ReportServiceImpl reportService;

    private final Instant now = Instant.parse("2026-06-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        reportService = new ReportServiceImpl(reportRepository, clock);
        lenient().when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createReportShouldPersistPendingReport() {
        UUID reporterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        CreateReportRequest request = CreateReportRequest.builder()
                .targetType(ReportTargetType.PRODUCT)
                .targetId(targetId)
                .reasonCategory(ReportReasonCategory.COUNTERFEIT)
                .description("fake goods")
                .build();

        ReportResponse response = reportService.createReport(reporterId, request);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertEquals(reporterId, captor.getValue().getReporterId());
        assertEquals(ReportStatus.PENDING, response.status());
    }

    @Test
    void listReportsWithStatusFilterShouldUseFindByStatus() {
        Report report = Report.builder().reporterId(UUID.randomUUID()).targetType(ReportTargetType.SHOP)
                .targetId(UUID.randomUUID()).reasonCategory(ReportReasonCategory.ABUSE).build();
        Page<Report> page = new PageImpl<>(List.of(report), PageRequest.of(0, 20), 1);
        when(reportRepository.findByStatus(ReportStatus.PENDING, PageRequest.of(0, 20,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))))
                .thenReturn(page);

        PagedResponse<ReportResponse> result = reportService.listReports(ReportStatus.PENDING, 0, 20);

        assertEquals(1, result.items().size());
    }

    @Test
    void resolveReportWhenPendingShouldResolve() {
        UUID reportId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Report report = Report.builder().reporterId(UUID.randomUUID()).targetType(ReportTargetType.PRODUCT)
                .targetId(UUID.randomUUID()).reasonCategory(ReportReasonCategory.MISLEADING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        ResolveReportRequest request = ResolveReportRequest.builder().outcome(ReportStatus.RESOLVED).note("handled").build();
        ReportResponse response = reportService.resolveReport(reportId, adminId, request);

        assertEquals(ReportStatus.RESOLVED, response.status());
        assertEquals(adminId, response.resolvedBy());
        assertEquals(now, response.resolvedAt());
    }

    @Test
    void resolveReportWhenRejectedOutcomeShouldReject() {
        UUID reportId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Report report = Report.builder().reporterId(UUID.randomUUID()).targetType(ReportTargetType.PRODUCT)
                .targetId(UUID.randomUUID()).reasonCategory(ReportReasonCategory.OTHER).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        ResolveReportRequest request = ResolveReportRequest.builder().outcome(ReportStatus.REJECTED).note("no violation").build();
        ReportResponse response = reportService.resolveReport(reportId, adminId, request);

        assertEquals(ReportStatus.REJECTED, response.status());
    }

    @Test
    void resolveReportWhenAlreadyResolvedShouldThrow() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().reporterId(UUID.randomUUID()).targetType(ReportTargetType.PRODUCT)
                .targetId(UUID.randomUUID()).reasonCategory(ReportReasonCategory.OTHER)
                .status(ReportStatus.RESOLVED).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        ResolveReportRequest request = ResolveReportRequest.builder().outcome(ReportStatus.RESOLVED).build();
        AppException exception = assertThrows(AppException.class,
                () -> reportService.resolveReport(reportId, UUID.randomUUID(), request));
        assertEquals(ErrorCode.REPORT_ALREADY_RESOLVED, exception.getErrorCode());
    }

    @Test
    void resolveReportWhenMissingShouldThrowNotFound() {
        UUID reportId = UUID.randomUUID();
        when(reportRepository.findById(reportId)).thenReturn(Optional.empty());

        ResolveReportRequest request = ResolveReportRequest.builder().outcome(ReportStatus.RESOLVED).build();
        AppException exception = assertThrows(AppException.class,
                () -> reportService.resolveReport(reportId, UUID.randomUUID(), request));
        assertEquals(ErrorCode.REPORT_NOT_FOUND, exception.getErrorCode());
    }
}
