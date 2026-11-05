package io.github.zzzzain777.retouch.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import io.github.zzzzain777.retouch.tool.RunStatus;

public interface AgentTurnRepository extends JpaRepository<AgentTurn, UUID> {

    /** 按会话限定：轮次 id 猜中了，换个会话也查不到。会话本身已按所有者校验过。 */
    Optional<AgentTurn> findByIdAndSessionId(UUID id, UUID sessionId);

    Optional<AgentTurn> findBySessionIdAndIdempotencyKey(UUID sessionId, String idempotencyKey);

    List<AgentTurn> findBySessionIdOrderByCreatedAtAsc(UUID sessionId, Limit limit);

    /** 确认、取消、重试、推进计划都先锁轮次行（SELECT … FOR UPDATE），同一轮的这些操作串行执行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AgentTurn t where t.id = :id")
    Optional<AgentTurn> lockById(UUID id);

    @Query("select t.id from AgentTurn t where t.status = :status and t.updatedAt < :before order by t.updatedAt")
    List<UUID> idsByStatusBefore(RunStatus status, Instant before, Limit limit);
}
