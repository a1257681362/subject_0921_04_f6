package com.evops.rental.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 通用流转请求：执行状态机配置表中 open_api=ON 的动作（新增状态/路径不改代码即生效）。
 */
public class TransitRequest {

    @NotBlank(message = "流转动作不能为空")
    @Size(max = 16, message = "流转动作长度不能超过 16")
    private String action;

    @Size(max = 500, message = "流转说明长度不能超过 500")
    private String detail;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
