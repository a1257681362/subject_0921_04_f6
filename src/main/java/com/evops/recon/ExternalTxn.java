package com.evops.recon;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 外部流水（渠道方账面）：渠道对账文件导入。external_ref_no 是渠道侧关联的业务
 * 参考号，用于和内部订单号对齐；同一 external_ref_no 重复导入按最新内容覆盖。
 */
@TableName("t_recon_txn_external")
public class ExternalTxn extends BizEntity {

    private String branchCode;
    private LocalDate bizDate;
    private String refNo;
    private String externalRefNo;
    private String txnType;
    private BigDecimal amount;
    private String status;
    private String rawText;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }
    public String getExternalRefNo() { return externalRefNo; }
    public void setExternalRefNo(String externalRefNo) { this.externalRefNo = externalRefNo; }
    public String getTxnType() { return txnType; }
    public void setTxnType(String txnType) { this.txnType = txnType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
}
