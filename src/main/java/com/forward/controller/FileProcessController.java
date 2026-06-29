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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/file")
public class FileProcessController {

    private final DuplicateCheckService duplicateCheckService;

    public FileProcessController(DuplicateCheckService duplicateCheckService) {
        this.duplicateCheckService = duplicateCheckService;
    }

    /**
     * GET /file/{fileId}/getMessageId?fileS3Path=FWB_DIRECT_DEBIT/.../file.xml
     *
     * Downloads the payment XML from S3 and extracts the MsgId from
     * the pain.008 GrpHdr element. The caller must supply the S3 path
     * because this service has no other way to locate the file.
     *
     * @param fileId     path variable — the file identifier (fileDataSeq)
     * @param fileS3Path query param  — bucket-relative S3 key of the payment XML
     */
    @GetMapping("/{fileId}/getMessageId")
    public ResponseEntity<GetMessageIdResponse> getMessageId(
            @PathVariable Long fileId,
            @RequestParam String fileS3Path) {

        System.out.println("[FileProcessController] getMessageId"
                + "  fileId=" + fileId + "  fileS3Path=" + fileS3Path);

        GetMessageIdResponse response = duplicateCheckService.getMessageId(fileId, fileS3Path);

        System.out.println("[FileProcessController] getMessageId → msgId=" + response.getMsgId());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /file/{fileId}/checkDuplicate
     *
     * Attempts INSERT into FILE_MESSAGE_ID(FILE_ID, CUST_ID, MSG_ID).
     * Unique index on (CUST_ID, MSG_ID) detects duplicates via
     * DataIntegrityViolationException.
     */
    @PostMapping("/{fileId}/checkDuplicate")
    public ResponseEntity<CheckDuplicateResponse> checkDuplicate(
            @PathVariable Long fileId,
            @RequestBody CheckDuplicateRequest request) {

        System.out.println("[FileProcessController] checkDuplicate"
                + "  fileId=" + fileId
                + "  custId=" + request.getCustId()
                + "  msgId="  + request.getMsgId());

        request.setFileId(fileId);  // path variable is authoritative
        CheckDuplicateResponse response = duplicateCheckService.checkDuplicate(request);

        System.out.println("[FileProcessController] checkDuplicate → isDuplicate="
                + response.getIsDuplicate());
        return ResponseEntity.ok(response);
    }

    // ── Existing endpoint ─────────────────────────────────────────────────────

    @PostMapping("/triggerToMq")
    public ResponseEntity<TriggerMsgToMqResponse> triggerMsgToMq(
            @RequestBody TriggerMsgToMqRequest req) {
        System.out.println("[FileProcessController] triggerMsgToMq called");
        return new ResponseEntity<>(new TriggerMsgToMqResponse(1), HttpStatus.CREATED);
    }
}
