package com.forward.service;

import com.forward.entity.FileMessageId;
import com.forward.model.CheckDuplicateRequest;
import com.forward.model.CheckDuplicateResponse;
import com.forward.model.GetMessageIdResponse;
import com.forward.repository.FileMessageIdRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Handles duplicate-file detection using the FILE_MESSAGE_ID table.
 *
 * Duplicate strategy — optimistic insert:
 *   1. Attempt to INSERT a row (fileId PK, custId, msgId).
 *   2. If it succeeds → the file is new (not a duplicate).
 *   3. If it throws DataIntegrityViolationException → a row with the same
 *      (CUST_ID, MSG_ID) unique index already exists → the file is a duplicate.
 *
 * This avoids a SELECT-then-INSERT race condition and keeps the logic simple.
 * The unique index on (CUST_ID, MSG_ID) is enforced at the database level, so
 * no application-level locking is needed.
 */
@Service
public class DuplicateCheckService {

    private final FileMessageIdRepository repository;

    public DuplicateCheckService(FileMessageIdRepository repository) {
        this.repository = repository;
    }

    /**
     * Retrieves the MSG_ID for an already-registered file entry.
     * Called by GET /file/{fileId}/getMessageId.
     *
     * In a real implementation this would look up the message ID from a payment
     * file metadata table or parse it from the XML. For now it returns the msgId
     * stored in FILE_MESSAGE_ID if the row exists, otherwise returns a placeholder
     * so the workflow can still proceed.
     *
     * @param fileId the file identifier (fileDataSeq from the incoming message)
     * @return response containing the msgId, or an empty string if not yet stored
     */
    public GetMessageIdResponse getMessageId(Long fileId) {
        return repository.findById(fileId)
                .map(row -> new GetMessageIdResponse(row.getMsgId()))
                .orElse(new GetMessageIdResponse(""));
    }

    /**
     * Checks whether the (custId, msgId) combination has already been processed.
     *
     * Attempts to INSERT a new row. The INSERT succeeds for new files and fails
     * with DataIntegrityViolationException for duplicates (unique constraint on
     * CUST_ID + MSG_ID).
     *
     * @param request contains fileId, custId, msgId
     * @return {@link CheckDuplicateResponse} with isDuplicate=false (new file)
     *         or isDuplicate=true (already seen)
     */
    public CheckDuplicateResponse checkDuplicate(CheckDuplicateRequest request) {
        System.out.println("[DuplicateCheckService] checking duplicate for"
                + " fileId=" + request.getFileId()
                + " custId=" + request.getCustId()
                + " msgId="  + request.getMsgId());
        try {
            repository.save(new FileMessageId(
                    request.getFileId(),
                    request.getCustId(),
                    request.getMsgId()
            ));
            System.out.println("[DuplicateCheckService] ✓ INSERT succeeded — file is NOT a duplicate");
            return new CheckDuplicateResponse(request.getFileId(), false);

        } catch (DataIntegrityViolationException e) {
            // Unique constraint (CUST_ID, MSG_ID) violated — this (custId, msgId) pair
            // was already inserted by a previous file, so the current file is a duplicate.
            System.out.println("[DuplicateCheckService] ✗ DataIntegrityViolation — file IS a duplicate"
                    + " | cause: " + e.getMostSpecificCause().getMessage());
            return new CheckDuplicateResponse(request.getFileId(), true);
        }
    }
}
