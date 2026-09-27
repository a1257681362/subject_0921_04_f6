package com.evops.recon.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDate;

/** 手工触发某网点某业务日的对账（补跑/重跑）。 */
public class ManualReconRequest {

    @NotBlank(message = "网点编码不能为空")
    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    @NotNull(message = "业务日期不能为空")
    private LocalDate bizDate;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
}
