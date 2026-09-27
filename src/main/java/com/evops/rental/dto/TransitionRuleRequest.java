package com.evops.rental.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 流转规则配置请求：新增/调整状态机合法路径（新增状态不改代码）。
 * 状态与动作名不区分大小写，落库统一大写。
 */
public class TransitionRuleRequest {

    @NotBlank(message = "源状态不能为空")
    @Size(max = 16, message = "源状态长度不能超过 16")
    private String fromStatus;

    @NotBlank(message = "流转动作不能为空")
    @Size(max = 16, message = "流转动作长度不能超过 16")
    private String action;

    @NotBlank(message = "目标状态不能为空")
    @Size(max = 16, message = "目标状态长度不能超过 16")
    private String toStatus;

    /** ON 生效 / OFF 停用；缺省 ON */
    @Pattern(regexp = "^(?i)(ON|OFF)$", message = "enabled 仅支持 ON/OFF")
    private String enabled;

    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getEnabled() { return enabled; }
    public void setEnabled(String enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
