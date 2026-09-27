package com.evops.gray.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 灰度规则新增/开关请求。scene 缺省 BRANCH_TREE；branchCode = '*' 表示全量。
 */
public class GrayRuleRequest {

    @Size(max = 32, message = "场景标识长度不能超过 32")
    private String scene;

    @NotBlank(message = "网点编码不能为空（'*' 表示全量）")
    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "ON|OFF", message = "状态只能是 ON 或 OFF")
    private String status;

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
