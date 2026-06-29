package com.forward.controller;

import com.forward.model.CheckDuplicateRequest;
import com.forward.model.CheckDuplicateResponse;
import com.forward.model.GetMessageIdResponse;
import com.forward.model.TriggerMsgToMqRequest;
import com.forward.model.TriggerMsgToMqResponse;
import com.forward.service.DuplicateCheckService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/file")
public class FileProcessController {

    private final DuplicateCheckService duplicateCheckService;

    public FileProcessController(DuplicateCheckService duplicateCheckService) {
        this.duplicateCheckService = duplicateCheckService;
    }

    /**
     * GET /file/{fileId}/getMessageId
     *
     * Returns the message ID (MsgId from the pain.008 GrpHdr) associated with
     * the given file. The workflow service calls this first so it has a msgId to
     * pass to checkDuplicate.
     *
     * @param fileId the file identifier (fileDataSeq from the incoming message)
     */
    @GetMapping("/{fileId}/getMessageId")
    public ResponseEntity<GetMessageIdResponse> getMessageId(@PathVariable Long fileId) {
        System.out.println("[FileProcessController] getMessageId called  fileId=" + fileId);
        GetMessageIdResponse response = duplicateCheckService.getMessageId(fileId);
        System.out.println("[FileProcessController] getMessageId response: msgId=" + response.getMsgId());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /file/{fileId}/checkDuplicate
     *
     * Attempts to INSERT a row into FILE_MESSAGE_ID(FILE_ID, CUST_ID, MSG_ID).
     *
     * The unique index on (CUST_ID, MSG_ID) is the duplicate guard:
     * - INSERT succeeds → file is new       → isDuplicate=false
     * - INSERT throws DataIntegrityViolation → duplicate detected → isDuplicate=true
     *
     * The fileId from the path takes precedence and is written into the request
     * object before delegating to the service.
     *
     * @param fileId  path variable — the file identifier
     * @param request body containing custId and msgId (fileId may also be present)
     */
    @PostMapping("/{fileId}/checkDuplicate")
    public ResponseEntity<CheckDuplicateResponse> checkDuplicate(
            @PathVariable Long fileId,
            @RequestBody CheckDuplicateRequest request) {

        System.out.println("[FileProcessController] checkDuplicate called"
                + "  fileId=" + fileId
                + "  custId=" + request.getCustId()
                + "  msgId="  + request.getMsgId());

        // Path variable is authoritative — reconcile with body
        request.setFileId(fileId);

        CheckDuplicateResponse response = duplicateCheckService.checkDuplicate(request);

        System.out.println("[FileProcessController] checkDuplicate response:"
                + "  fileId=" + response.getFileId()
                + "  isDuplicate=" + response.getIsDuplicate());

        return ResponseEntity.ok(response);
    }

    // ── Existing endpoint — kept as-is ───────────────────────────────────────

    @PostMapping("/triggerToMq")
    public ResponseEntity<TriggerMsgToMqResponse> triggerMsgToMq(
            @RequestBody TriggerMsgToMqRequest req) {
        System.out.println("[FileProcessController] triggerMsgToMq called");
        // TODO: implement actual MQ dispatch
        return new ResponseEntity<>(new TriggerMsgToMqResponse(1), HttpStatus.CREATED);
    }
}
