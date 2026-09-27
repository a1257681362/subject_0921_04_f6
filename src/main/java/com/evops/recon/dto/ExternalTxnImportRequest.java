package com.evops.recon.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 渠道对账文件导入请求：一次导入某网点某业务日的一批外部流水。
 * 同一 externalRefNo 重复导入按最新内容覆盖（幂等更新，不新增行）。
 */
public class ExternalTxnImportRequest {

    @NotBlank(message = "网点编码不能为空")
    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    @NotNull(message = "业务日期不能为空")
    private LocalDate bizDate;

    @NotEmpty(message = "外部流水列表不能为空")
    @Valid
    private List<ExternalTxnItemRequest> txns;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public List<ExternalTxnItemRequest> getTxns() { return txns; }
    public void setTxns(List<ExternalTxnItemRequest> txns) { this.txns = txns; }
}
