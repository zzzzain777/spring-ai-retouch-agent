package io.github.zzzzain777.retouch.asset;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    /** 按主键与所有者联合查询，他人的素材一律查不到（对外表现为 404）。 */
    Optional<Asset> findByIdAndUserId(UUID id, UUID userId);

    List<Asset> findByUserIdOrderByCreatedAtDesc(UUID userId, Limit limit);

    List<Asset> findByIdInAndUserId(Collection<UUID> ids, UUID userId);
}
