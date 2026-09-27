package com.evops.recon;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 内部流水（本方账面）：从租车单结算/冲销事件按网点+业务日抽取，ref_no 即订单号。
 * (ref_no, txn_type) 唯一：同一业务参考号同一类型只抽一笔，重复拉取幂等。
 */
@TableName("t_recon_txn_internal")
public class InternalTxn extends BizEntity {

    public static final String TYPE_SETTLE = "SETTLE";
    public static final String TYPE_REVERSE = "REVERSE";

    private String branchCode;
    private LocalDate bizDate;
    private String refNo;
    private String txnType;
    private BigDecimal amount;
    private String status;
    private Long orderId;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }
    public String getTxnType() { return txnType; }
    public void setTxnType(String txnType) { this.txnType = txnType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
}
