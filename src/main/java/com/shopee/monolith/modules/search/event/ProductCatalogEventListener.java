package com.shopee.monolith.modules.search.event;

import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.event.ProductCatalogSnapshotEvent;
import com.shopee.monolith.modules.product.event.ProductListingStatusChangedEvent;
import com.shopee.monolith.modules.search.service.EmbeddingIndexService;
import com.shopee.monolith.modules.search.service.SearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to product catalog events and drives both Elasticsearch index and
 * pgvector embedding index updates idempotently after business transaction commit.
 *
 * <p>Both handlers run {@code @Async} on the shared event executor and open
 * their own {@code REQUIRES_NEW} transaction when DB access is needed.
 * Failures are logged and left for Spring Modulith publication replay — they do
 * NOT propagate back to the publishing transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductCatalogEventListener {

    private final SearchIndexService searchIndexService;
    private final EmbeddingIndexService embeddingIndexService;

    /**
     * Handles full catalog snapshot: upsert ES document + re-index pgvector embedding.
     * Fired on product create, update, publish, unpublish and delete (with status in payload).
     */
    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProductCatalogSnapshot(ProductCatalogSnapshotEvent event) {
        log.debug("ProductCatalogSnapshotEvent productId={} status={}", event.productId(), event.status());
        if (event.status() == ProductStatus.ACTIVE) {
            searchIndexService.upsertDocument(event);
            embeddingIndexService.indexProduct(event);
        } else {
            searchIndexService.deleteDocument(event.productId());
            embeddingIndexService.removeProduct(event.productId());
        }
    }

    /**
     * Handles listing status change: removes document from ES and pgvector when
     * product becomes INACTIVE or DELETED (without a full snapshot payload).
     */
    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProductListingStatusChanged(ProductListingStatusChangedEvent event) {
        log.debug("ProductListingStatusChangedEvent productId={} newStatus={}", event.productId(), event.newStatus());
        if (event.newStatus() != ProductStatus.ACTIVE) {
            searchIndexService.deleteDocument(event.productId());
            embeddingIndexService.removeProduct(event.productId());
        }
    }
}
