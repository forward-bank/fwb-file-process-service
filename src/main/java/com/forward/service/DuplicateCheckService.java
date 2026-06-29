package com.forward.service;

import com.forward.entity.FileMessageId;
import com.forward.model.CheckDuplicateRequest;
import com.forward.model.CheckDuplicateResponse;
import com.forward.model.GetMessageIdResponse;
import com.forward.repository.FileMessageIdRepository;
import com.forward.s3.S3FileDownloader;
import com.forward.xml.Pain008MsgIdExtractor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Handles duplicate-file detection using the FILE_MESSAGE_ID table.
 *
 * getMessageId flow:
 *   1. Download the payment XML from S3 using the provided fileS3Path.
 *   2. Parse MsgId from <GrpHdr><MsgId> in the pain.008 document.
 *   3. Return it so the caller can pass it to checkDuplicate.
 *
 * checkDuplicate flow (optimistic insert):
 *   1. Attempt to INSERT (fileId, custId, msgId).
 *   2. INSERT succeeds  → file is new        → isDuplicate=false
 *   3. DataIntegrityViolationException thrown → duplicate detected → isDuplicate=true
 *      (unique index on CUST_ID + MSG_ID fires)
 */
@Service
public class DuplicateCheckService {

    private final FileMessageIdRepository repository;
    private final S3FileDownloader        s3FileDownloader;
    private final Pain008MsgIdExtractor   msgIdExtractor;

    public DuplicateCheckService(FileMessageIdRepository repository,
                                 S3FileDownloader s3FileDownloader,
                                 Pain008MsgIdExtractor msgIdExtractor) {
        this.repository       = repository;
        this.s3FileDownloader = s3FileDownloader;
        this.msgIdExtractor   = msgIdExtractor;
    }

    /**
     * Downloads the payment XML from S3 and extracts the MsgId from GrpHdr.
     *
     * @param fileId     the file identifier (for logging)
     * @param fileS3Path bucket-relative S3 key of the payment XML
     * @return response containing the parsed MsgId
     */
    public GetMessageIdResponse getMessageId(Long fileId, String fileS3Path) {
        System.out.println("[DuplicateCheckService] getMessageId"
                + "  fileId=" + fileId + "  fileS3Path=" + fileS3Path);

        byte[] xmlBytes = s3FileDownloader.download(fileS3Path);
        String msgId    = msgIdExtractor.extractMsgId(xmlBytes);

        System.out.println("[DuplicateCheckService] ✓ msgId=" + msgId);
        return new GetMessageIdResponse(msgId);
    }

    /**
     * Checks whether the (custId, msgId) combination has already been processed.
     *
     * Uses a direct native INSERT (not JPA save) so the unique constraint on
     * (CUST_ID, MSG_ID) always fires on a duplicate — save() would silently
     * UPDATE the existing row because FILE_ID is the same.
     */
    public CheckDuplicateResponse checkDuplicate(CheckDuplicateRequest request) {
        System.out.println("[DuplicateCheckService] checkDuplicate"
                + "  fileId=" + request.getFileId()
                + "  custId=" + request.getCustId()
                + "  msgId="  + request.getMsgId());
        try {
            repository.insertFileMessageId(
                    request.getFileId(),
                    request.getCustId(),
                    request.getMsgId()
            );
            System.out.println("[DuplicateCheckService] ✓ INSERT succeeded — NOT a duplicate");
            return new CheckDuplicateResponse(request.getFileId(), false);

        } catch (DataIntegrityViolationException e) {
            System.out.println("[DuplicateCheckService] ✗ DataIntegrityViolation — IS a duplicate"
                    + " | " + e.getMostSpecificCause().getMessage());
            return new CheckDuplicateResponse(request.getFileId(), true);
        }
    }
}
