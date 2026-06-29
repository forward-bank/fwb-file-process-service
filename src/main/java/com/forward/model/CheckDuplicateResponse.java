package com.forward.model;

/**
 * Response for POST /file/{fileId}/checkDuplicate.
 */
public class CheckDuplicateResponse {

    private Long    fileId;
    private Boolean isDuplicate;

    public CheckDuplicateResponse() {}

    public CheckDuplicateResponse(Long fileId, Boolean isDuplicate) {
        this.fileId      = fileId;
        this.isDuplicate = isDuplicate;
    }

    public Long    getFileId()               { return fileId; }
    public Boolean getIsDuplicate()          { return isDuplicate; }

    public void setFileId(Long fileId)             { this.fileId      = fileId; }
    public void setIsDuplicate(Boolean isDuplicate){ this.isDuplicate = isDuplicate; }
}
