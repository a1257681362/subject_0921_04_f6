package com.evops.recon.dto;

import javax.validation.constraints.Size;

/** 对账单确认（次日早晨核对无误后确认冻结），备注可选。 */
public class StatementConfirmRequest {

    @Size(max = 500, message = "确认备注长度不能超过 500")
    private String remark;

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
