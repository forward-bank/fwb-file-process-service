package com.forward.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps to the FILE_MESSAGE_ID table in the public schema of the FILE_PROCESS database.
 *
 * DDL (for reference — table is managed externally, ddl-auto=none):
 * <pre>
 * CREATE TABLE public.FILE_MESSAGE_ID (
 *     FILE_ID  BIGINT       NOT NULL,
 *     CUST_ID  BIGINT       NOT NULL,
 *     MSG_ID   VARCHAR(50)  NOT NULL,
 *     CONSTRAINT pk_file_message_id PRIMARY KEY (FILE_ID),
 *     CONSTRAINT uq_cust_msg       UNIQUE       (CUST_ID, MSG_ID)
 * );
 * </pre>
 *
 * Duplicate detection relies on the unique index on (CUST_ID, MSG_ID).
 * A file is considered a duplicate when an INSERT throws DataIntegrityViolationException
 * because a row with the same (CUST_ID, MSG_ID) already exists.
 */
@Entity
@Table(name = "\"FILE_MESSAGE_ID\"", schema = "public")
public class FileMessageId {

    @Id
    @Column(name = "\"FILE_ID\"", nullable = false)
    private Long fileId;

    @Column(name = "\"CUST_ID\"", nullable = false)
    private Long custId;

    @Column(name = "\"MSG_ID\"", nullable = false, length = 50)
    private String msgId;

    public FileMessageId() {}

    public FileMessageId(Long fileId, Long custId, String msgId) {
        this.fileId = fileId;
        this.custId = custId;
        this.msgId  = msgId;
    }

    public Long   getFileId() { return fileId; }
    public Long   getCustId() { return custId; }
    public String getMsgId()  { return msgId; }
}
