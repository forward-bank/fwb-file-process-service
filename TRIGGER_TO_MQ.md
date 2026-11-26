
## 2. Service: `FileProcessService.triggerAndFormatMsgToQueue`

Annotated `@Transactional` (BDD transaction manager, `REQUIRES_NEW`, rollback on `ApplicationException`). This Spring transaction covers only the Spring-managed repository work, i.e. the final file status update. Per-transaction updates use a separate `EntityManager` (step 5).

### Step 1: Open a custom entity manager

`customEntityManagementFactory.create()` in try-with-resources: a standalone JPA `EntityManager` for manual transaction control, closed at the end.

### Step 2: Load file metadata

`getFileMetadataByFileId(fileId)` uses `findById` on `FileProcessMetadata`. Not found throws `EXECUTION_ERROR_POSTGRES_NOT_FOUND` (404). `outputChannelCode` is read from this record.

### Step 3: `registerTotalsToMessagingService`

Runs before any trigger is sent.

- Runs `countTransactionsForFileByLbi` (counts per LBI/batch) and `findFirstByCustIdAndFileId`.
- Either result empty: throws `EXECUTION_ERROR_COULD_NOT_FIND_DEBULK_DATA_FOR_FILE` (404).
- Reads `fileType` from the first row. Null throws the same 404 error.
- Builds `RegisterFileCountRequest` (fileId, custId, per-LBI counts, fileType, header presence). Null header presence defaults to `PRESENT`.
- `MessagingServiceClient.registerFileCountsMessage` POSTs it via `RestTemplate` to `messagingServiceHost + registerBatchCountsUrl`.
- Any exception becomes `EXECUTION_ERROR_COULD_NOT_DISPATCH_METADATA` (500). The returned status code is ignored; only a thrown exception counts as failure.

### Step 4: Page through transactions

- `currentTime = Instant.now(clock)` is set once and reused for all updates.
- `PageRequest.of(page, batchSizeToMq)` where `page` is always 0 and the size is the `batch.to.trigger.size` property.
- Query: `findAllByCustIdAndFileIdAndStatusNotLike(custId, fileId, "DISPATCHED", pageRequest)` returns undispatched rows as a `FileDebulkMetadataOnly` projection. Dispatched rows drop out of the filter, so page 0 stays correct.
- A null page returns early with the count so far, without setting the file status.
- Each row goes to `dispatchTriggerToQueue` and the counter is incremented.
- After each page, `clearEntityManager()` frees memory.
- Loop continues while the page was full (`size == batchSize`).

```java
/**
 * Service method to fetch transactions that were saved from Debulking and dispatch triggers to TXN workflow
 *
 * @param fileId - a file Id
 * @param custId - a customer Id
 * @return - TriggerToMqResponse containing the number of transactions processed
 * @throws ApplicationException - in the event that updating the processing status in the database or dispatching to queue fails
 */
@Transactional(value = BDD_TRANSACTION_MANAGER, rollbackFor = ApplicationException.class, propagation = Propagation.REQUIRES_NEW)
public TriggerToMqResponse triggerAndFormatMsgToQueue(Long custId, Long fileId, ApplicationHeaderPresence headerPresence) throws ApplicationException {

    try (CustomEntityManagement customEntityManagement = customEntityManagementFactory.create()) {
        FileProcessMetadata fileProcessMetadata = getFileMetadataByFileId(fileId);
        String outputChannelCode = fileProcessMetadata.getOutputChannelCode();
        // Register the total number of transactions and batches for the file in messaging service before dispatching
        // triggers about each transaction to TXN workflow
        registerTotalsToMessagingService(custId, fileId, headerPresence);

        int processedTransactions = 0;
        int page = 0;
        Page<FileDebulkMetadataOnly> batchPage;
        List<FileDebulkMetadataOnly> batchData;
        Instant currentTime = Instant.now(clock);

        do {
            PageRequest pageRequest = PageRequest.of(page, applicationConfiguration.getBatchSizeToMq());
            // Fetch records in batches to avoid having a large resultset in memory
            batchPage = fileDebulkRepository.findAllByCustIdAndFileIdAndStatusNotLike(custId, fileId, DISPATCHED, pageRequest);
            if (batchPage == null) {
                return new TriggerToMqResponse(processedTransactions);
            }
            batchData = batchPage.getContent();
            if (!batchData.isEmpty()) {
                // Start dispatching triggers about each transaction downstream
                for (FileDebulkMetadataOnly transaction : batchData) {
                    dispatchTriggerToQueue(transaction, customEntityManagement, currentTime, outputChannelCode);
                    processedTransactions++;
                }
            }
            customEntityManagement.clearEntityManager();
        } while (batchData.size() == applicationConfiguration.getBatchSizeToMq());

        // Update processing status for the file
        fileProcessMetadata.setStatus(PAYMENTS_DISPATCHED.toString());
        fileProcessMetadata.setUpdTime(currentTime);
        try {
            fileProcessMetadataRepository.saveAndFlush(fileProcessMetadata);
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR + EXECUTION_ERROR_COULD_NOT_SAVE.getCode() +
                            EXECUTION_ERROR_COULD_NOT_SAVE.getMessageWithValue(Encode.forJava(fileProcessMetadata.getFileId().toString()))
                            + ", custId [{}] {}" + EXCEPTION_LABEL + e, Encode.forJava(custId.toString()),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            throw new ApplicationException(EXECUTION_ERROR_COULD_NOT_SAVE.getCode(),
                    EXECUTION_ERROR_COULD_NOT_SAVE.getMessageWithValue(fileProcessMetadata.getFileId().toString()),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new TriggerToMqResponse(processedTransactions);
    }
}
   /**
    * Method to update the processing status for each transaction and dispatch trigger about the transaction downstream.
    * Each time this method is executed, a new transaction will start to enable the ability to rollback the DB update in case
    * dispatching to the queue fails.
    *
    * @param transaction            - a transaction to process
    * @param customEntityManagement - a custom entity manager
    * @param currentTime            - now
    * @throws ApplicationException - in the event that fetching file metadata
    */
   @VisibleForTesting // It shouldn't be, but I can't be bothered to rewrite the tests right now
   void dispatchTriggerToQueue(
           FileDebulkMetadataOnly transaction,
           CustomEntityManagement customEntityManagement,
           Instant currentTime,
           String outputChannelCode
) throws ApplicationException {

      TriggerMsgToMQ message = TriggerMsgToMQ.builder()
              .fileId(transaction.getFileId())
              .custId(transaction.getCustId())
              .lti(transaction.getLti())
              .lbi(transaction.getLbi())
              .psnCode(transaction.getPsnCode())
              .psnLevel(transaction.getPsnLevel())
              .fileType(transaction.getFileType())
              .btchBookg(transaction.getBtchBookg())
              .outputChannelCode(outputChannelCode)
              .build();

      try {
         customEntityManagement.startTransaction();
         customEntityManagement.updateSingleTransaction(transaction, currentTime);
         mqTriggerService.postTriggerToQueue(message, transaction.getFileId());
         customEntityManagement.commitTransaction();
         log.debug("Dispatching metadata sent to the queue: {}", Encode.forJava(message.toString()));
      } catch (ApplicationException e) {
         customEntityManagement.rollbackTransaction();
         log.error(MESSAGING_FILE_SERVICE_TECH_ERROR + EXECUTION_ERROR_WHILE_DISPATCHING_TRANSACTION.getCode() +
                         EXECUTION_ERROR_WHILE_DISPATCHING_TRANSACTION.getMessage() + " {} for custId [{}], fileId [{}], LBI [{}], LTI [{}]" + EXCEPTION_LABEL + e, HttpStatus.INTERNAL_SERVER_ERROR,
                 Encode.forJava(message.getCustId().toString()),
                 Encode.forJava(message.getFileId().toString()), Encode.forJava(message.getLbi()),
                 Encode.forJava(message.getLti()));
         throw e;
      }
   }

```

## CustomEntityManagement class :
```java
public class CustomEntityManagement implements Closeable {

    private final EntityManager entityManager;
    private final Set<FileDebulkMetadata> debulkMetadataSet = new HashSet<>();
    private Integer batchSize;
    private EntityTransaction txn;

    public CustomEntityManagement(EntityManagerFactory entityManagerFactory, Integer batchSize) {
        entityManager = entityManagerFactory.createEntityManager();
        this.batchSize = batchSize;
    }

    public CustomEntityManagement(EntityManagerFactory entityManagerFactory) {
        entityManager = entityManagerFactory.createEntityManager();
    }

    public void closeSession() {
        entityManager.close();
    }

    public void rollbackTransaction() throws ApplicationException {

        try {
            txn.rollback();
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR + EXECUTION_ERROR_UNABLE_TO_ROLLBACK_TRANSACTION.getCode() +
                    EXECUTION_ERROR_UNABLE_TO_ROLLBACK_TRANSACTION.getMessage() +
                    HttpStatus.INTERNAL_SERVER_ERROR + EXCEPTION_LABEL + e);
            throw new ApplicationException(
                    EXECUTION_ERROR_UNABLE_TO_ROLLBACK_TRANSACTION.getCode(),
                    EXECUTION_ERROR_UNABLE_TO_ROLLBACK_TRANSACTION.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public void updateSingleTransaction(FileDebulkMetadataOnly txn, Instant currentTime) throws ApplicationException {
        try {
            int updates = entityManager.createQuery("update FileDebulkMetadata set status = :status, updTime=:currentTime where id=:id")
                    .setParameter("status", DISPATCHED)
                    .setParameter("currentTime", currentTime)
                    .setParameter("id", txn.getId())
                    .executeUpdate();
            validateSingleRowUpdated(updates, txn);
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR + EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY.getCode() +
                    EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY.getMessage() +
                    HttpStatus.INTERNAL_SERVER_ERROR + EXCEPTION_LABEL + e);
            throw new ApplicationException(
                    EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY.getCode(),
                    EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void validateSingleRowUpdated(int updates, FileDebulkMetadataOnly txn) throws ApplicationException {
        if (updates != 1) {
            log.error("Expected to update single row in MST_OP_FILE_DEBULK_META table but "
                    + "updated {} rows for id={}", Encode.forJava(String.valueOf(updates)),
                    Encode.forJava(txn.getId().toString()));
            throw new ApplicationException(
                    EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY.getCode(),
                    EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public void clearEntityManager() throws ApplicationException {

        try {
            entityManager.clear();
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR + EXECUTION_ERROR_UNABLE_TO_CLEAR_ENTITY_MANAGER.getCode() +
                    EXECUTION_ERROR_UNABLE_TO_CLEAR_ENTITY_MANAGER.getMessage() +
                    HttpStatus.INTERNAL_SERVER_ERROR + EXCEPTION_LABEL + e);
            throw new ApplicationException(
                    EXECUTION_ERROR_UNABLE_TO_CLEAR_ENTITY_MANAGER.getCode(),
                    EXECUTION_ERROR_UNABLE_TO_CLEAR_ENTITY_MANAGER.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public void persistTransaction(FileDebulkMetadata transactionToSave) throws ApplicationException {
        debulkMetadataSet.add(transactionToSave);
        if (debulkMetadataSet.size() == batchSize) {
            persistAllTransactions();
        }
    }

    public void startTransaction() throws ApplicationException {

        try {
            txn = entityManager.getTransaction();
            txn.begin();
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR, EXECUTION_ERROR_UNABLE_TO_START_TRANSACTION.getCode() +
                    EXECUTION_ERROR_UNABLE_TO_START_TRANSACTION.getMessage() +
                    HttpStatus.INTERNAL_SERVER_ERROR + EXCEPTION_LABEL + e);
            throw new ApplicationException(
                    EXECUTION_ERROR_UNABLE_TO_START_TRANSACTION.getCode(),
                    EXECUTION_ERROR_UNABLE_TO_START_TRANSACTION.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public void commitTransaction() throws ApplicationException {

        try {
            txn.commit();
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR, EXECUTION_ERROR_UNABLE_TO_COMMIT_TRANSACTION.getCode() +
                    EXECUTION_ERROR_UNABLE_TO_COMMIT_TRANSACTION.getMessage() +
                    HttpStatus.INTERNAL_SERVER_ERROR + EXCEPTION_LABEL + e);
            throw new ApplicationException(
                    EXECUTION_ERROR_UNABLE_TO_COMMIT_TRANSACTION.getCode(),
                    EXECUTION_ERROR_UNABLE_TO_COMMIT_TRANSACTION.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public void persistAllTransactions() throws ApplicationException {

        try {
            startTransaction();
            // Persist each entity in the set before releasing it from memory
            debulkMetadataSet.forEach(this.entityManager::persist);
            entityManager.flush();
            entityManager.clear();
            commitTransaction();
            debulkMetadataSet.clear();
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR, EXECUTION_ERROR_UNABLE_TO_SAVE_BATCH.getCode() +
                    EXECUTION_ERROR_UNABLE_TO_SAVE_BATCH.getMessage() +
                    HttpStatus.INTERNAL_SERVER_ERROR + EXCEPTION_LABEL + e);
            throw new ApplicationException(
                    EXECUTION_ERROR_UNABLE_TO_SAVE_BATCH.getCode(),
                    EXECUTION_ERROR_UNABLE_TO_SAVE_BATCH.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public void updateFileDebulkMetadata(List<FileDebulkMetadata> fileDebulkMetadataList) throws ApplicationException {
        try {
            startTransaction();
            fileDebulkMetadataList.forEach(this.entityManager::merge);
            entityManager.flush();
            entityManager.clear();
            commitTransaction();
        } catch (Exception e) {
            log.error(MESSAGING_FILE_SERVICE_TECH_ERROR + EXECUTION_ERROR_COULD_NOT_UPDATE_FILE_DEBULK_METADATA.getCode() +
                    EXECUTION_ERROR_COULD_NOT_UPDATE_FILE_DEBULK_METADATA.getMessage() +
                    HttpStatus.INTERNAL_SERVER_ERROR + EXCEPTION_LABEL + e);
            throw new ApplicationException(
                    EXECUTION_ERROR_COULD_NOT_UPDATE_FILE_DEBULK_METADATA.getCode(),
                    EXECUTION_ERROR_COULD_NOT_UPDATE_FILE_DEBULK_METADATA.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    @Override
    public void close() {
        this.closeSession();
    }
}
```

## dispatchTriggerToQueue execution scenarios :

When the DB update or the MQ send fails, neither ends up done. The rollback only undoes the DB update. It never undoes a message that was already sent.

`startTransaction` → `updateSingleTransaction (status = DISPATCHED)` → `postTriggerToQueue (MQ send)` → `commitTransaction`

The update runs first, then the send, and the commit comes last.

## Failure cases

| Failure point | DB result | MQ result | Consistent? |
|---|---|---|---|
| `startTransaction()` fails | Nothing changed | Nothing sent | Yes |
| `updateSingleTransaction` fails (error, or update count != 1) | Rolled back | Nothing sent, because the update comes first | Yes |
| `postTriggerToQueue` fails (JSON or MQ error) | Rolled back, so the row stays not dispatched | Not sent | Yes |
| `commitTransaction()` fails after a successful send | Rolled back, so the row stays not dispatched | Already sent | No, a retry sends a duplicate |

## Example Scenario

1100 rows share the same `custId` and `fileId`, split into three batches (LBI). All start with a status other than `DISPATCHED`. Page size is 500.

| Batch A: 400 | Batch B: 600 | Batch C: 100 |
|:---:|:---:|:---:|

Batch IDs do not affect the query or the loop. The query filters only on `custId`, `fileId` and `status`.

**Before the loop:** `registerTotalsToMessagingService` runs `countTransactionsForFileByLbi`, which returns A=400, B=600, C=100. These totals are sent once to the messaging service. This is the only place batch grouping is used.

## The loop, iteration by iteration

Every iteration uses `PageRequest.of(0, 500)`.

| Iteration | Undispatched rows in DB | Query returns | Dispatched this pass | Running total | Continue? |
|---|---|---|---|---|---|
| 1 | 1100 | 500 | 500 | 500 | Yes (500 == 500) |
| 2 | 600 | 500 | 500 | 1000 | Yes (500 == 500) |
| 3 | 100 | 100 | 100 | 1100 | No (100 != 500) |

| Pass 1: 500 | Pass 2: 500 | Pass 3: 100 |
|:---:|:---:|:---:|

### Each pass

1. **Query:** roughly `WHERE cust_id=? AND file_id=? AND status NOT LIKE 'DISPATCHED' LIMIT 500 OFFSET 0`, plus a count query.
2. **Per row (`dispatchTriggerToQueue`):** start a manual transaction, set the row to `DISPATCHED`, send the MQ message, commit.
3. **End of pass:** `clearEntityManager()`.

### Why page 0 every time works

All 500 rows from the previous pass were committed as `DISPATCHED`, so they no longer match the filter. The next 500 undispatched rows are now at the start of the result set. Incrementing `page` would skip rows.

### After iteration 3

The file status is set to `PAYMENTS_DISPATCHED` and the response is `noOfTransactionsDispatched = 1100`.

### Step 5: `dispatchTriggerToQueue` (per transaction)

1. Build `TriggerMsgToMQ`: fileId, custId, lti, lbi, psnCode, psnLevel, fileType, btchBookg, outputChannelCode.
2. `startTransaction()` begins a manual JPA transaction.
3. `updateSingleTransaction` runs `update FileDebulkMetadata set status='DISPATCHED', updTime=:now where id=:id`. An update count other than 1 throws `EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY`.
4. `mqTriggerService.postTriggerToQueue(message, fileId)`:
    - Serializes to indented JSON with Jackson. Failure throws `EXECUTION_ERROR_JSON_PROCESSING_EXCEPTION` (500).
    - Sends via `clusteredTextMessageSender.dispatchMessage(queueName, MESSAGE_KEY, json)`. Queue name is `bdd.mq.dispatching.queue.name`.
    - On `VanquishRuntimeException` it walks the cause chain. An IBM `MQException` logs an MQ error (channel, host, queue manager, message); otherwise a technical error. Either throws `EXECUTION_ERROR_COULD_NOT_DISPATCH_METADATA` (500).
5. `commitTransaction()` commits the DB update only after the MQ send succeeds.
6. On `ApplicationException`: `rollbackTransaction()`, log with custId, fileId, LBI, LTI, then rethrow. The whole request aborts.

### Step 6: Mark the file dispatched

Sets `FileProcessMetadata.status = PAYMENTS_DISPATCHED` and `updTime = currentTime`, then `saveAndFlush`. Failure throws `EXECUTION_ERROR_COULD_NOT_SAVE` (500).

### Step 7: Return

`new TriggerToMqResponse(processedTransactions)`

## Error summary

| Where | Error | HTTP |
|---|---|---|
| Load file metadata | `EXECUTION_ERROR_POSTGRES_NOT_FOUND` | 404 |
| Register totals: no debulk rows / null fileType | `EXECUTION_ERROR_COULD_NOT_FIND_DEBULK_DATA_FOR_FILE` | 404 |
| Register totals: messaging service call fails | `EXECUTION_ERROR_COULD_NOT_DISPATCH_METADATA` | 500 |
| Row update count != 1 | `EXECUTION_ERROR_UNABLE_TO_UPDATE_ENTITY` | 500 |
| JSON serialization | `EXECUTION_ERROR_JSON_PROCESSING_EXCEPTION` | 500 |
| MQ send fails | `EXECUTION_ERROR_COULD_NOT_DISPATCH_METADATA` | 500 |
| Final file status save fails | `EXECUTION_ERROR_COULD_NOT_SAVE` | 500 |