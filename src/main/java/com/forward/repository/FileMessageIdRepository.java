package com.forward.repository;

import com.forward.entity.FileMessageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface FileMessageIdRepository extends JpaRepository<FileMessageId, Long> {

    /**
     * Issues a direct INSERT without a prior SELECT.
     *
     * Spring Data JPA's save() checks isNew() by calling findById() first when
     * the entity has a manually-assigned @Id (no @GeneratedValue). That SELECT
     * finds the existing row on the second call, so save() emits an UPDATE
     * instead of a new INSERT, and the unique constraint on (CUST_ID, MSG_ID)
     * never fires.
     *
     * This native INSERT bypasses that behaviour completely. The database sees
     * a fresh INSERT every time, so:
     *   - First call  → INSERT succeeds → not a duplicate
     *   - Second call → INSERT violates unique(CUST_ID, MSG_ID) → DataIntegrityViolationException
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO public."file_message_id" ("file_id", "cust_id", "msg_id")
            VALUES (:fileId, :custId, :msgId)
            """,
            nativeQuery = true)
    void insertFileMessageId(@Param("fileId") Long fileId,
                             @Param("custId") Long custId,
                             @Param("msgId")  String msgId);
}
