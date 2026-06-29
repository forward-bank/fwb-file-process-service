package com.forward.model;

/**
 * Returned by GET /file/{fileId}/getMessageId.
 *
 * Intentionally a plain class (not a record) so additional fields can be added
 * later without breaking JSON deserialization on the caller side.
 */
public class GetMessageIdResponse {

    private String msgId;

    public GetMessageIdResponse() {}

    public GetMessageIdResponse(String msgId) {
        this.msgId = msgId;
    }

    public String getMsgId()           { return msgId; }
    public void   setMsgId(String msgId) { this.msgId = msgId; }
}
