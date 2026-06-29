package com.forward.model;

/**
 * Request body for POST /file/{fileId}/checkDuplicate.
 *
 * fileId is also carried in the URL path variable; it is included here so the
 * service layer receives a single self-contained object and path + body are
 * always in sync (the controller reconciles them).
 */
public class CheckDuplicateRequest {

    private Long   fileId;
    private Long   custId;
    private String msgId;

    public CheckDuplicateRequest() {}

    public CheckDuplicateRequest(Long fileId, Long custId, String msgId) {
        this.fileId = fileId;
        this.custId = custId;
        this.msgId  = msgId;
    }

    public Long   getFileId()          { return fileId; }
    public Long   getCustId()          { return custId; }
    public String getMsgId()           { return msgId; }

    public void setFileId(Long fileId)     { this.fileId = fileId; }
    public void setCustId(Long custId)     { this.custId = custId; }
    public void setMsgId(String msgId)     { this.msgId  = msgId; }
}
