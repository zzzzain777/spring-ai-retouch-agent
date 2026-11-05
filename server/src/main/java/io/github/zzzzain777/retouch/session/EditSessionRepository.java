package io.github.zzzzain777.retouch.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EditSessionRepository extends JpaRepository<EditSession, UUID> {

    /** 按主键与所有者联合查询：他人的会话一律查不到，对外表现为 404。 */
    Optional<EditSession> findByIdAndUserId(UUID id, UUID userId);

    /** 改会话前先锁行（SELECT … FOR UPDATE）：同一会话的编辑、撤销、计划推进串行执行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EditSession s where s.id = :id and s.userId = :userId")
    Optional<EditSession> lockByIdAndUserId(UUID id, UUID userId);

    List<EditSession> findByUserIdOrderByUpdatedAtDesc(UUID userId, Limit limit);
}
