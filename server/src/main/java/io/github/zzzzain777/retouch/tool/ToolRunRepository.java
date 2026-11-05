package io.github.zzzzain777.retouch.tool;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ToolRunRepository extends JpaRepository<ToolRun, UUID> {

    Optional<ToolRun> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ToolRun r where r.id = :id")
    Optional<ToolRun> lockById(UUID id);
}
