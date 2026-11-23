Pain008 Payment XML File Debulking :
one of the main functionality of fwb-file-process-service is to debulk the incoming payment xml file.
fwb-file-process service recieves the debulking request through the message queue.

Payment XML file Debulking steps :
1. Download the payment xml file from S3 path

2. Parse the xml file and use the corresponding mapping xml file to map element to corresponding Java class
   e.g. payment-file-parser-mappings-pain_008_001_08.xml

3. Run validation rules along the mapping e.g. run validation for App Header, Group Header, Batch Rules, Txn Rules etc.

Note that Pain008 parsing follows a streaming/callback driven approach where parsing and validation happen
progressively as XML sections are encountered, rather than load entire Pain008 into one giant
Java object and then validate afterwards.

```
<parser-mappings>
  <java-model-classes>
    <class id="initiation" type="com.forward.direct.debit.model.DirectDebitInitiation"/>
    <class id="groupHeader" type="com.forward.direct.debit.model.GroupHeader"/>
    <class id="paymentInstruction" type="com.forward.direct.debit.model.PaymentInstruction"/>
    <class id="transaction" type="com.forward.direct.debit.model.DirectDebitTransaction"/>
  </java-model-classes>

  <element-mapping class="initiation" path="Document/CstmrDrctDbtInitn"/>

  <element-mapping class="groupHeader" path="Document/CstmrDrctDbtInitn/GrpHdr" setter="groupHeader">
    <element-map name="MsgId"       setter="msgId"/>
    <element-map name="CreDtTm"     setter="creDtTm"/>
    <element-map name="NbOfTxs"     setter="nbOfTxs"/>
    <element-map name="CtrlSum"     setter="ctrlSum"/>
    <element-map name="InitgPty/Nm" setter="initiatingPartyName"/>
  </element-mapping>

  <element-mapping class="paymentInstruction" path="Document/CstmrDrctDbtInitn/PmtInf"
                   setter="paymentInstructions" list="true">
    <element-map name="PmtInfId"                          setter="pmtInfId"/>
    <element-map name="PmtMtd"                            setter="pmtMtd"/>
    <element-map name="BtchBookg"                         setter="batchBooking"/>
    <element-map name="NbOfTxs"                           setter="nbOfTxs"/>
    <element-map name="CtrlSum"                           setter="ctrlSum"/>
    <element-map name="PmtTpInf/SvcLvl/Cd"                setter="serviceLevel"/>
    <element-map name="PmtTpInf/LclInstrm/Cd"             setter="localInstrument"/>
    <element-map name="PmtTpInf/SeqTp"                    setter="sequenceType"/>
    <element-map name="ReqdColltnDt"                      setter="requestedCollectionDate"/>
    <element-map name="Cdtr/Nm"                           setter="creditorName"/>
    <element-map name="CdtrAcct/Id/IBAN"                  setter="creditorIban"/>
    <element-map name="CdtrAgt/FinInstnId/BICFI"          setter="creditorBic"/>
    <element-map name="ChrgBr"                            setter="chargeBearer"/>
    <element-map name="CdtrSchmeId/Id/PrvtId/Othr/Id"     setter="creditorSchemeId"/>
  </element-mapping>

  <element-mapping class="transaction" path="Document/CstmrDrctDbtInitn/PmtInf/DrctDbtTxInf"
                   setter="transactions" list="true">
    <element-map name="PmtId/EndToEndId"                   setter="endToEndId"/>
    <element-map name="InstdAmt"                           setter="amount"/>
    <element-map name="InstdAmt@Ccy"                       setter="currency"/>
    <element-map name="DrctDbtTx/MndtRltdInf/MndtId"       setter="mandateId"/>
    <element-map name="DrctDbtTx/MndtRltdInf/DtOfSgntr"    setter="mandateSignDate"/>
    <element-map name="DbtrAgt/FinInstnId/BICFI"           setter="debtorBic"/>
    <element-map name="Dbtr/Nm"                            setter="debtorName"/>
    <element-map name="DbtrAcct/Id/IBAN"                   setter="debtorIban"/>
    <element-map name="RmtInf/Ustrd"                       setter="remittanceInfo"/>
  </element-mapping>
</parser-mappings>
```


```declarative
Read XML
↓
Map App Header
↓
Validate App Header
↓
Map Group Header
↓
Validate Group Header
↓
Map Payment Information
↓
Validate Payment Batch
↓
For Each Transaction
    Map Transaction
    ↓
    Validate Transaction
    ↓
    Build PaymentDataSource
    ↓
    Create FileDebulkMetadata
↓
Final File Reconciliation Validation
↓
Debulking Complete
```

# PAIN.008.001.08 Debulking Validation Rules by Processing Stage

This document shows exactly which validation rules are executed during each stage of the PAIN.008 SEPA Direct Debit debulking lifecycle.

| Processing Step | When Executed | Validation Rules |
|---|---|---|
| 1. App Header Validation | Immediately after AppHdr is mapped into Java objects. | `FF08` Message Definition Identifier Validation (pain.008.001.08) |
| 2. Group Header Validation | Immediately after GrpHdr is mapped. | `FF05` Initiating Party Organisation ID Validation<br>`FF06` Initiating Party Private ID Validation |
| 3. Batch-Level Validation | After PmtInf is completely mapped and LBI generated. | `ChargeBearerOverRuled`<br>`PaymentTypeInformationOverRuled`<br>`CreditorSchmIDOverRuled`<br>`CreditorAddressCombinationError`<br>`CreditorPostalAddressOverRuled`<br>`UltimateCreditorOrgIDOverRuled`<br>`UltimateCreditorPvtIDOverRuled`<br>`CreditorAgentFinIDOverRuled`<br>`CreditorAgentFinIDInvalidID`<br>`DebtorAndCreditorAccountSame`<br>`InvalidPaymentRequestDate` |
| 4. Customer Preference Validation | After PmtInf mapping and before processing transactions. | `RequestedDateExceedDiariedLimit`<br>`RequestedDateExceedsSALimit` |
| 5. External Content Validation | After batch information becomes available. | `ContentValidationServiceClient` Validation Result<br>External IN Reason / PSN Code Assignment |
| 6. Transaction-Level Validation | Immediately after each DrctDbtTxInf transaction is mapped. | `AmendmentDetailsMissing`<br>`TranxUltimateCreditorOrgIDOverRuled`<br>`TranxUltimateCreditorPvtIDOverRuled`<br>`DebtorAddressCombinationForbidden`<br>`DebtorAddressMissing`<br>`DebtorOrgIDOverruled`<br>`DebtorPvtIDOverruled`<br>`UltimateDebtorOrgIDOverRuled`<br>`UltimateDebtorPvtIDOverRuled`<br>`RemittanceInformationOverRuled`<br>`InvalidCurrencyFormat`<br>`DebtorAgentFinIDOverRuled`<br>`DebtorAgentFinIDInvalidID`<br>`OrgnDebAgntOverRuled`<br>`SDDDebtorDetailsMissing` |
| 7. Final File Reconciliation Validation | Executed only after every transaction has been parsed. | `FF03` Group Header Number Of Transactions Validation<br>`FF04` Group Header Control Sum Validation |
| 8. Final Batch Reconciliation Validation | Executed after all transactions within the batch are available. | `ControlSumMismatch`<br>`NumberOfTransMismatch` |


## Validation Timeline Summary

| Validation Category | Number of Rules |
|---|:---:|
| 🔵 App Header Validation | 1 |
| 🔵 Group Header Validation | 2 |
| 🔵 Batch-Level Validation | 11 |
| 🔵 Customer Preference Validation | 2 |
| 🟡 External Content Validation | *Dynamic* |
| 🔵 Transaction-Level Validation | 15 |
| 🔵 File Reconciliation Validation | 2 |
| 🔵 Batch Reconciliation Validation | 2 |
| 🟦 **Total Explicit Validation Rules** | **33+** |

below is the FileDebulkMetadata class which represents a single transaction within the pain008 payment file,
note that it also stores File Context(fileId) and Batch Context(lbi) for every single transaction.

A logical batch identifier (LBI) is generated per PmtInf block. 
All transactions inside the same batch share the same LBI.
```
@Entity
@Table( name = "MST_FILE_DEBULK_META",
schema = "FWB_FILE_PROCESS")
public class FileDebulkMetadata{

	@Column(name = "ID")
	private Long id;
	
	@Column(name = "CUST_ID", nullable = false)
	private Long custId;
	
	@Column(name = "FILE_ID", nullable = false)
	private Long fileId;
	
	@Column(name = "LBI", nullable = false)
	private String lbi;
	
	@Column(name = "LTI", nullable = false)
	private String lti;
	
	@Column(name = "PAYMENT_DATA")
	private String paymentData; // this is serialized PaymentDataSource
	
	@Column(name = "FILE_TYPE", nullable = false)
	@Enumerated(EnumType.STRING)
	private FileType fileType;

}
```


Note that most of Validation failure during each of the above steps do not cause Debulking to stop,
they generate FSN, BTCH_PSN, TXN_PSN, and transactions are still persisted to MST_FILE_DEBULK_META table.

Also the response from Debulking will be Response Code e.g. ACCP, DU02, FF02, FF03, FF04 etc.

