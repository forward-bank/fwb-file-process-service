# FWB File Process Service

A Spring Boot microservice that sits at the centre of the Direct Debit payment file pipeline. It provides two REST endpoints consumed by the Camunda workflow service during the `duplicate_check_task` stage: one to extract the ISO 20022 `MsgId` from a pain.008 XML file stored in S3, and one to perform a database-level duplicate check. It also listens on an IBM MQ queue for debulking requests from the same workflow.

---

## Table of Contents

1. [How It Fits in the System](#how-it-fits-in-the-system)
2. [Duplicate Check Flow](#duplicate-check-flow)
3. [Why Native INSERT Instead of JPA save()](#why-native-insert-instead-of-jpa-save)
4. [Project Structure](#project-structure)
5. [REST API](#rest-api)
6. [Database — FILE_PROCESS](#database--file_process)
7. [S3 / LocalStack Integration](#s3--localstack-integration)
8. [IBM MQ Integration](#ibm-mq-integration)
9. [Spring Configuration](#spring-configuration)
10. [application.properties Reference](#applicationproperties-reference)
11. [Running Locally](#running-locally)

---

## How It Fits in the System

```
fwb-direct-debit-workflow-service  (Camunda BPMN engine)
        │
        │  duplicate_check_task  (Service Task)
        │  DuplicateCheckTaskDefinition.execute()
        │
        │  Step 1 ──────────────────────────────────────────────────────────────►
        │  GET /file/{fileId}/getMessageId?fileS3Path=FWB_DIRECT_DEBIT/.../file.xml
        │                                      fwb-file-process-service
        │                                        S3FileDownloader.download()
        │                                        Pain008MsgIdExtractor.extractMsgId()
        │  ◄──────────────────────────────────── { msgId: "MSGID-20260625-001" }
        │
        │  Step 2 ──────────────────────────────────────────────────────────────►
        │  POST /file/{fileId}/checkDuplicate
        │       { fileId, custId, msgId }
        │                                      DuplicateCheckService
        │                                        repository.insertFileMessageId()
        │                                        → INSERT into FILE_MESSAGE_ID
        │                                        → DataIntegrityViolation? → duplicate
        │  ◄──────────────────────────────────── { fileId, isDuplicate: false|true }
        │
        ▼
  [is_file_duplicate?] gateway
     false → debulking_request_task
     true  → End (Duplicate File)
```

---

## Duplicate Check Flow

```
  ┌──────────────────────────────────────────────────────────────────────────────┐
  │  DuplicateCheckTaskDefinition (workflow service)                              │
  │                                                                               │
  │  triggerMessage = (InputMessage) executionContext.get("TRIGGER_MESSAGE")      │
  │  fileId    = triggerMessage.fileDataSeq()                                     │
  │  fileS3Path = triggerMessage.fileS3Path()                                     │
  └───────────────────────────────┬──────────────────────────────────────────────┘
                                  │
                GET /file/{fileId}/getMessageId?fileS3Path=...
                                  │
  ┌───────────────────────────────▼──────────────────────────────────────────────┐
  │  FileProcessController.getMessageId(fileId, fileS3Path)                       │
  │    │                                                                          │
  │    ├─ S3FileDownloader.download(fileS3Path)                                   │
  │    │     bucket : fwb-payments-dev                                            │
  │    │     key    : FWB_DIRECT_DEBIT/PAYMENT_FILES/.../file.xml                 │
  │    │     →  byte[] xmlBytes                                                   │
  │    │                                                                          │
  │    └─ Pain008MsgIdExtractor.extractMsgId(xmlBytes)                            │
  │          DOM parse  →  getElementsByTagNameNS("*", "MsgId")                  │
  │          →  "MSGID-20260625-001"                                              │
  └───────────────────────────────┬──────────────────────────────────────────────┘
                                  │ { msgId: "MSGID-20260625-001" }
                                  │
               POST /file/{fileId}/checkDuplicate
                     { fileId, custId, msgId }
                                  │
  ┌───────────────────────────────▼──────────────────────────────────────────────┐
  │  FileProcessController.checkDuplicate(fileId, request)                        │
  │    │                                                                          │
  │    └─ DuplicateCheckService.checkDuplicate(request)                           │
  │            repository.insertFileMessageId(fileId, custId, msgId)              │
  │                                                                               │
  │            INSERT INTO public."FILE_MESSAGE_ID"                               │
  │              ("FILE_ID", "CUST_ID", "MSG_ID")                                 │
  │              VALUES (314900, 0, 'MSGID-20260625-001')                         │
  │                                                                               │
  │            CASE 1 — first time:                                               │
  │              INSERT succeeds → { fileId: 314900, isDuplicate: false }         │
  │                                                                               │
  │            CASE 2 — same (custId, msgId) seen again:                          │
  │              UNIQUE constraint on (CUST_ID, MSG_ID) fires                     │
  │              DataIntegrityViolationException caught                           │
  │              → { fileId: 314900, isDuplicate: true }                          │
  └──────────────────────────────────────────────────────────────────────────────┘
```

---

## Why Native INSERT Instead of JPA save()

Spring Data JPA's `save()` calls `findById()` before deciding whether to INSERT or UPDATE when the entity has a **manually-assigned `@Id`** (no `@GeneratedValue`). On the second call with the same `FILE_ID`, the SELECT finds the existing row and JPA emits an `UPDATE` instead of a new INSERT. The unique constraint on `(CUST_ID, MSG_ID)` never fires because no second row is ever attempted.

The fix is a native `@Query` INSERT that bypasses the pre-check SELECT entirely:

```
repository.insertFileMessageId(fileId, custId, msgId)
  ↓
INSERT INTO public."FILE_MESSAGE_ID" ("FILE_ID", "CUST_ID", "MSG_ID")
VALUES (:fileId, :custId, :msgId)
```

Every call hits the database as a fresh INSERT:
- First call with any `(custId, msgId)` pair → INSERT succeeds → `isDuplicate = false`
- Subsequent call with the same `(custId, msgId)` → `UNIQUE(CUST_ID, MSG_ID)` fires → `DataIntegrityViolationException` → `isDuplicate = true`

Note: `FILE_ID` is the primary key, but the duplicate signal comes from the **composite unique index** on `(CUST_ID, MSG_ID)` — two different files from the same customer with the same `MsgId` are duplicates, regardless of `FILE_ID`.

---

## Project Structure

```
src/main/java/com/forward/
│
├── FileProcessApplication.java             # @SpringBootApplication entry point
│
├── controller/
│   └── FileProcessController.java          # REST endpoints (getMessageId, checkDuplicate, triggerToMq)
│
├── service/
│   ├── DuplicateCheckService.java          # Orchestrates S3 download + MsgId extraction + DB insert
│   └── FileProcessService.java             # Placeholder for future service logic
│
├── repository/
│   └── FileMessageIdRepository.java        # JPA repository with native INSERT query
│
├── entity/
│   └── FileMessageId.java                  # JPA entity mapping FILE_MESSAGE_ID table
│
├── model/
│   ├── GetMessageIdResponse.java           # Response: { msgId }
│   ├── CheckDuplicateRequest.java          # Request:  { fileId, custId, msgId }
│   ├── CheckDuplicateResponse.java         # Response: { fileId, isDuplicate }
│   ├── TriggerMsgToMqRequest.java          # Request:  { fileId, custId }
│   ├── TriggerMsgToMqResponse.java         # Response: { numberOfTxnDispatched }
│   └── DebulkingResponse.java              # MQ response: { status, errorCode }
│
├── s3/
│   └── S3FileDownloader.java               # Downloads file bytes from S3 by bucket-relative key
│
├── xml/
│   └── Pain008MsgIdExtractor.java          # DOM-parses pain.008 XML → extracts <GrpHdr><MsgId>
│
├── config/
│   └── S3Config.java                       # S3Client bean (LocalStack or real AWS)
│
├── mq/
│   ├── MQConfig.java                       # Connection POJO + queue name constants
│   └── listener/
│       └── FileProcessRequestListener.java # Inbound MQ listener for debulking requests
│
└── debulk/
    └── FileDebulkingProcessor.java         # Processes debulking request payloads
```

---

## REST API

### GET `/file/{fileId}/getMessageId`

Downloads the payment XML from S3 and extracts the ISO 20022 `MsgId` from `<GrpHdr><MsgId>`.

**Query parameter:** `fileS3Path` — bucket-relative S3 object key of the payment XML file.

**Example:**
```
GET /file/314900/getMessageId?fileS3Path=FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/I1234567890123.FWB.pain00800108.PM.xml_452387
```

**Response `200 OK`:**
```json
{
  "msgId": "MSGID-20260625-001"
}
```

The `msgId` field is the `<MsgId>` value from the first `<GrpHdr>` element in the pain.008 XML. `GetMessageIdResponse` is a plain class (not a record) so additional fields can be added later without breaking callers.

---

### POST `/file/{fileId}/checkDuplicate`

Checks whether a `(custId, msgId)` combination has already been processed by attempting a native INSERT into `FILE_MESSAGE_ID`. Returns `isDuplicate=false` on the first occurrence and `isDuplicate=true` on any subsequent attempt with the same combination.

**Path variable:** `fileId` — takes precedence over the `fileId` field in the request body.

**Request body:**
```json
{
  "fileId": 314900,
  "custId": 0,
  "msgId":  "MSGID-20260625-001"
}
```

**Response `200 OK` — first call (new file):**
```json
{
  "fileId": 314900,
  "isDuplicate": false
}
```

**Response `200 OK` — second call (duplicate):**
```json
{
  "fileId": 314900,
  "isDuplicate": true
}
```

---

### POST `/file/triggerToMq`

Stub endpoint — currently returns a fixed response. Intended for dispatching debulking jobs.

**Request body:** `{ "fileId": 123, "custId": 456 }`  
**Response `201 Created`:** `{ "numberOfTxnDispatched": 1 }`

---

## Database — FILE_PROCESS

### Connection

```
host     : localhost:5432
database : FILE_PROCESS   (case-sensitive — created with double-quoted identifier)
username : camunda
password : camunda
```

The JDBC URL must use the exact case: `jdbc:postgresql://localhost:5432/FILE_PROCESS`. PostgreSQL stores database names as created — if the DB was created with `CREATE DATABASE "FILE_PROCESS"`, lowercase variants will not connect.

### Table — `FILE_MESSAGE_ID`

```sql
CREATE TABLE public."FILE_MESSAGE_ID" (
    "FILE_ID"  BIGINT       NOT NULL,
    "CUST_ID"  BIGINT       NOT NULL,
    "MSG_ID"   VARCHAR(50)  NOT NULL,
    CONSTRAINT pk_file_message_id PRIMARY KEY ("FILE_ID"),
    CONSTRAINT uq_cust_msg       UNIQUE       ("CUST_ID", "MSG_ID")
);
```

| Column | Type | Constraint | Role |
|--------|------|-----------|------|
| `FILE_ID` | `BIGINT` | Primary Key | Identifies the payment file (`fileDataSeq` from incoming message) |
| `CUST_ID` | `BIGINT` | NOT NULL | Customer identifier |
| `MSG_ID` | `VARCHAR(50)` | NOT NULL | ISO 20022 `MsgId` from `<GrpHdr>` in the pain.008 XML |

The **unique index on `(CUST_ID, MSG_ID)`** is the duplicate guard. Two payment files submitted by the same customer with the same `MsgId` are considered duplicates regardless of `FILE_ID`.

### JPA Entity

Column names use double-quoted identifiers in `@Column` annotations to match the uppercase names PostgreSQL stores on disk:

```java
@Entity
@Table(name = "\"FILE_MESSAGE_ID\"", schema = "public")
public class FileMessageId {
    @Id
    @Column(name = "\"FILE_ID\"", nullable = false)
    private Long fileId;
    ...
}
```

`spring.jpa.hibernate.ddl-auto=none` — Hibernate never modifies the table schema.

### Native INSERT query

```java
@Modifying
@Transactional
@Query(value = """
    INSERT INTO public."file_message_id" ("file_id", "cust_id", "msg_id")
    VALUES (:fileId, :custId, :msgId)
    """, nativeQuery = true)
void insertFileMessageId(@Param("fileId") Long fileId,
                         @Param("custId") Long custId,
                         @Param("msgId")  String msgId);
```

This is called directly by `DuplicateCheckService` instead of `save()`. See [Why Native INSERT](#why-native-insert-instead-of-jpa-save).

---

## S3 / LocalStack Integration

### How the object key is resolved

```
Property        : aws.s3.bucket = fwb-payments-dev
fileS3Path param: FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/file.xml
                  └── used directly as the S3 object key ──┘

Effective GET   : s3://fwb-payments-dev/FWB_DIRECT_DEBIT/PAYMENT_FILES/.../file.xml
```

A leading `/` on `fileS3Path` is stripped automatically.

### LocalStack (local development)

`aws.localstack.enabled=true` makes `S3Config` build an `S3Client` pointing at `http://localhost:4566` with `pathStyleAccessEnabled=true` (required because LocalStack doesn't resolve virtual-hosted bucket subdomains).

### Production

`aws.localstack.enabled=false` — `S3Client` is built with `DefaultCredentialsProvider` (env vars → `~/.aws/credentials` → EC2/ECS instance profile).

### MsgId extraction

`Pain008MsgIdExtractor` uses JAXP DOM parsing with:
- `setNamespaceAware(true)` — handles any namespace prefix on the pain.008 elements
- `getElementsByTagNameNS("*", "MsgId")` — namespace-agnostic element lookup
- XXE protection via `setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`

---

## IBM MQ Integration

### Queues

| Queue | Direction | Purpose |
|-------|-----------|---------|
| `FILE.PROCESS.SERVICE.REQUEST.QUEUE` | Inbound | Receives debulking requests from the workflow service |
| `FILE.PROCESS.SERVICE.RESPONSE.QUEUE` | Outbound | Sends debulking results back to the workflow service |

### Debulking flow (MQ)

`FileProcessRequestListener` listens on `FILE.PROCESS.SERVICE.REQUEST.QUEUE`. When a message arrives:
1. Reads `JMSCorrelationID` — used to match the response back
2. Parses the JSON body into a `Map` containing `custId`, `fileId`, `fileS3Path`
3. Passes the map to `FileDebulkingProcessor.process()`
4. Writes the result JSON to `FILE.PROCESS.SERVICE.RESPONSE.QUEUE` with the same `JMSCorrelationID`

Consumer and producer use **two separate JMS connections** to prevent producer activity from interfering with consumer session acknowledgment.

**Poison-message guard** — messages with `JMSXDeliveryCount > 5` are discarded to prevent infinite redelivery loops.

---

## Spring Configuration

### S3Config

```
@Configuration
S3Config
  @Bean S3Client s3Client()
    if aws.localstack.enabled → LocalStack (localhost:4566), path-style, static credentials
    else                      → DefaultCredentialsProvider (production AWS chain)
```

### DuplicateCheckService dependencies

```
DuplicateCheckService
  ← FileMessageIdRepository  (Spring Data JPA — native INSERT)
  ← S3FileDownloader         (@Component — downloads XML bytes)
  ← Pain008MsgIdExtractor    (@Component — parses MsgId from XML)
```

---

## application.properties Reference

```properties
# ─── Server ─────────────────────────────────────────────────────────────────
server.port=8081

# ─── PostgreSQL ──────────────────────────────────────────────────────────────
# Database name is case-sensitive — must match the exact name in pgAdmin
spring.datasource.url=jdbc:postgresql://localhost:5432/FILE_PROCESS
spring.datasource.username=camunda
spring.datasource.password=camunda
spring.datasource.driver-class-name=org.postgresql.Driver

spring.jpa.hibernate.ddl-auto=none          # never touch the schema
spring.jpa.show-sql=true                    # log all generated SQL
spring.jpa.properties.hibernate.default_schema=public

# ─── AWS S3 / LocalStack ─────────────────────────────────────────────────────
# true  → S3Client points at LocalStack (local dev)
# false → S3Client uses DefaultCredentialsProvider (production)
aws.localstack.enabled=true
aws.localstack.endpoint=http://localhost:4566
aws.region=us-east-1
aws.accessKeyId=test        # any value — LocalStack ignores credentials
aws.secretAccessKey=test
aws.s3.bucket=fwb-payments-dev
```

All properties can be overridden at runtime:
- Environment variable: `AWS_S3_BUCKET=fwb-payments-prod`
- System property: `-Daws.s3.bucket=fwb-payments-prod`
- Profile file: `application-prod.properties`

---

## Running Locally

### Prerequisites

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Runtime |
| Maven | 3.8+ | Build |
| PostgreSQL | 15+ | `FILE_PROCESS` database on `localhost:5432` |
| IBM MQ | 9.x | Queue Manager `MY.TEST.QMNGR` on `localhost:1414` |
| LocalStack | 3.x | Mocks S3 on `localhost:4566` |

### Build and run

```bash
mvn clean package -DskipTests
java -jar target/fwb-file-process-service-1.0-SNAPSHOT.jar
```

### Test the duplicate check manually

First call — should return `isDuplicate: false`:
```bash
# Step 1 — get the MsgId from the payment XML
curl "http://localhost:8081/file/314900/getMessageId?fileS3Path=FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/your-file.xml"
# Response: {"msgId":"MSGID-20260625-001"}

# Step 2 — check duplicate (first time)
curl -X POST http://localhost:8081/file/314900/checkDuplicate \
     -H "Content-Type: application/json" \
     -d '{"custId":0,"msgId":"MSGID-20260625-001"}'
# Response: {"fileId":314900,"isDuplicate":false}
```

Second call with same `(custId, msgId)` — should return `isDuplicate: true`:
```bash
curl -X POST http://localhost:8081/file/314900/checkDuplicate \
     -H "Content-Type: application/json" \
     -d '{"custId":0,"msgId":"MSGID-20260625-001"}'
# Response: {"fileId":314900,"isDuplicate":true}
```

Verify the row in pgAdmin:
```sql
SELECT * FROM public."FILE_MESSAGE_ID";
--  FILE_ID | CUST_ID | MSG_ID
--  314900  |    0    | MSGID-20260625-001
```
