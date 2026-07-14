package com.shopee.monolith.modules.wallet.mapper;

import com.shopee.monolith.modules.wallet.dto.response.WalletResponse;
import com.shopee.monolith.modules.wallet.dto.response.WalletTransactionResponse;
import com.shopee.monolith.modules.wallet.entity.Wallet;
import com.shopee.monolith.modules.wallet.entity.WalletTransaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface WalletMapper {

    @Mapping(source = "id", target = "walletId")
    WalletResponse toResponse(Wallet wallet);

    WalletTransactionResponse toResponse(WalletTransaction transaction);
}
