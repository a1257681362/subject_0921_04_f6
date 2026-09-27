package com.evops.recon.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 差异手工核销请求。原因强制必填：调度员核销时必须写明分歧成因/处理结论，
 * 服务层与库表留痕双重兜底，杜绝无理由清零。
 */
public class DiffResolveRequest {

    @NotBlank(message = "核销原因必填：手工核销必须填写原因")
    @Size(max = 500, message = "核销原因长度不能超过 500")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
