package io.github.zzzzain777.retouch.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EditHistoryRepository extends JpaRepository<EditHistory, UUID> {

    Optional<EditHistory> findBySessionIdAndSeq(UUID sessionId, int seq);

    List<EditHistory> findBySessionIdOrderBySeqDesc(UUID sessionId);

    @Query("select coalesce(max(h.seq), 0) from EditHistory h where h.sessionId = :sessionId")
    int maxSeq(UUID sessionId);

    /** 新编辑截断重做分支。 */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from EditHistory h where h.sessionId = :sessionId and h.seq > :seq")
    int deleteAfter(UUID sessionId, int seq);

    /** 只保留最近若干条。 */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from EditHistory h where h.sessionId = :sessionId and h.seq <= :seq")
    int deleteUpTo(UUID sessionId, int seq);
}
