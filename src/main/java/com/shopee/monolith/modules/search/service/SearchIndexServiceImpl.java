package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.modules.product.event.ProductCatalogSnapshotEvent;
import com.shopee.monolith.modules.search.document.ProductDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexServiceImpl implements SearchIndexService {

    // Optional: tests disable Elasticsearch autoconfiguration to speed up unrelated IT suites.
    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;

    private static final IndexCoordinates PRODUCTS_INDEX = IndexCoordinates.of("products");

    @Override
    public void upsertDocument(ProductCatalogSnapshotEvent event) {
        try {
            ElasticsearchOperations elasticsearchOperations = requireElasticsearch();
            ensureIndex(elasticsearchOperations);
            ProductDocument doc = ProductDocument.builder()
                    .productId(event.productId().toString())
                    .name(event.name())
                    .description(event.description())
                    .categoryPath(event.categoryPath())
                    .brand(event.brand())
                    .attributes(event.attributes())
                    .minPrice(event.minPrice())
                    .maxPrice(event.maxPrice())
                    .shopId(event.shopId() != null ? event.shopId().toString() : null)
                    .shopName(event.shopName())
                    .shopRating(event.shopRating())
                    .coverImageUrl(event.coverImageUrl())
                    .coverMediaId(event.coverMediaId() != null ? event.coverMediaId().toString() : null)
                    .status(event.status() != null ? event.status().name() : null)
                    .createdAt(null)
                    .build();
            elasticsearchOperations.save(doc, PRODUCTS_INDEX);
            log.debug("ES upsert OK productId={}", event.productId());
        } catch (Exception ex) {
            log.warn("ES upsert failed productId={} — will retry via publication replay", event.productId(), ex);
        }
    }

    @Override
    public void deleteDocument(UUID productId) {
        try {
            requireElasticsearch().delete(productId.toString(), PRODUCTS_INDEX);
            log.debug("ES delete OK productId={}", productId);
        } catch (Exception ex) {
            log.warn("ES delete failed productId={} — will retry via publication replay", productId, ex);
        }
    }

    private ElasticsearchOperations requireElasticsearch() {
        ElasticsearchOperations operations = elasticsearchOperationsProvider.getIfAvailable();
        if (operations == null) {
            throw new IllegalStateException("Elasticsearch is not available");
        }
        return operations;
    }

    private void ensureIndex(ElasticsearchOperations elasticsearchOperations) {
        IndexOperations indexOps = elasticsearchOperations.indexOps(ProductDocument.class);
        try {
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
            }
        } catch (Exception ex) {
            log.warn("ES index check/create failed — proceeding anyway", ex);
        }
    }
}
