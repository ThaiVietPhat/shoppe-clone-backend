package com.shopee.monolith.modules.moderation.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.moderation.dto.request.CreateReportRequest;
import com.shopee.monolith.modules.moderation.dto.request.ResolveReportRequest;
import com.shopee.monolith.modules.moderation.dto.response.ReportResponse;
import com.shopee.monolith.modules.moderation.model.ReportStatus;

import java.util.UUID;

public interface ReportService {

    ReportResponse createReport(UUID reporterId, CreateReportRequest request);

    PagedResponse<ReportResponse> listReports(ReportStatus status, int page, int size);

    ReportResponse resolveReport(UUID reportId, UUID adminId, ResolveReportRequest request);
}
