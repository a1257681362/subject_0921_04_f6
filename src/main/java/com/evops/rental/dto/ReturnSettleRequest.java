package com.evops.rental.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * 还车登记 + 结算：还车交接信息之外携带结算金额。
 */
public class ReturnSettleRequest extends HandoverRequest {

    @DecimalMin(value = "0.00", message = "结算金额不能为负")
    @Digits(integer = 10, fraction = 2, message = "结算金额整数位最多 10 位、小数最多 2 位")
    private BigDecimal settleAmount;

    public BigDecimal getSettleAmount() { return settleAmount; }
    public void setSettleAmount(BigDecimal settleAmount) { this.settleAmount = settleAmount; }
}
