package com.shopee.monolith.modules.inventory.service;

import com.shopee.monolith.modules.inventory.dto.command.ConfirmInventoryCommand;
import com.shopee.monolith.modules.inventory.dto.command.ReleaseInventoryCommand;
import com.shopee.monolith.modules.inventory.dto.command.ReserveInventoryCommand;
import com.shopee.monolith.modules.inventory.dto.command.RestockInventoryCommand;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.inventory.dto.internal.InventoryStockSummary;
import com.shopee.monolith.modules.inventory.dto.response.InventoryMovementResponse;
import com.shopee.monolith.modules.inventory.dto.response.InventoryResponse;
import com.shopee.monolith.modules.user.model.Role;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InventoryService {

    InventoryResponse getInventoryByVariantId(UUID variantId, UUID currentUserId, Role currentRole);

    InventoryResponse createInventory(UUID variantId, int initialStock, UUID currentUserId, Role currentRole);

    InventoryResponse updateAvailableStock(UUID variantId, int availableStock, UUID currentUserId, Role currentRole);

    void reserve(List<ReserveInventoryCommand> commands);

    void confirm(List<ConfirmInventoryCommand> commands);

    void release(List<ReleaseInventoryCommand> commands);

    /**
     * Restocks already-confirmed (sold) units after an approved return/dispute — distinct from
     * {@link #release}, which only reverses a still-open RESERVED reservation.
     */
    void restock(List<RestockInventoryCommand> commands);

    /**
     * Batch load stock summaries by variant IDs. Read-only, no locks.
     * Used by ProductService to build checkout eligibility hints.
     * Checkout MUST revalidate this at reservation time — do not trust this value for correctness.
     *
     * @param variantIds list of variant IDs to query
     * @return map of variantId → InventoryStockSummary; missing keys = inventory not found
     */
    Map<UUID, InventoryStockSummary> getStockSummariesByVariantIds(List<UUID> variantIds);

    /**
     * Paged audit ledger of stock movements for one variant, newest first.
     * Ownership-checked: only the owning seller or an admin can read it.
     */
    PagedResponse<InventoryMovementResponse> listMovements(
            UUID variantId, UUID currentUserId, Role currentRole, Pageable pageable);
}

