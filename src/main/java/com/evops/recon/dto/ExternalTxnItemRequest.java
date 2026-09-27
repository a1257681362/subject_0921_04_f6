package com.evops.recon.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 单笔外部流水（渠道方账面原文）。
 * externalRefNo 是渠道侧关联的本方业务参考号（订单号），勾稽按它配对。
 */
public class ExternalTxnItemRequest {

    /** 渠道单自身流水号（可选） */
    @Size(max = 40, message = "渠道流水号长度不能超过 40")
    private String refNo;

    @NotBlank(message = "渠道关联业务参考号不能为空")
    @Size(max = 40, message = "业务参考号长度不能超过 40")
    private String externalRefNo;

    /** 渠道原文类型：SETTLE/PAYMENT 结算，REFUND 退款冲销 */
    @NotBlank(message = "流水类型不能为空")
    @Size(max = 16, message = "流水类型长度不能超过 16")
    private String txnType;

    @NotNull(message = "流水金额不能为空")
    @Digits(integer = 10, fraction = 2, message = "流水金额整数位最多 10 位、小数最多 2 位")
    private BigDecimal amount;

    @NotBlank(message = "流水状态不能为空")
    @Size(max = 16, message = "流水状态长度不能超过 16")
    private String status;

    @Size(max = 500, message = "原始报文长度不能超过 500")
    private String rawText;

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
