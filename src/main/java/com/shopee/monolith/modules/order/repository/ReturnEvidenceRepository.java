package com.shopee.monolith.modules.order.repository;

import com.shopee.monolith.modules.order.entity.ReturnEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReturnEvidenceRepository extends JpaRepository<ReturnEvidence, UUID> {

    List<ReturnEvidence> findAllByReturnId(UUID returnId);

    List<ReturnEvidence> findAllByReturnIdIn(Collection<UUID> returnIds);
}
