package com.shopee.monolith.modules.voucher.mapper;

import com.shopee.monolith.modules.voucher.dto.response.VoucherResponse;
import com.shopee.monolith.modules.voucher.entity.Voucher;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface VoucherMapper {

    VoucherResponse toResponse(Voucher voucher);
}
