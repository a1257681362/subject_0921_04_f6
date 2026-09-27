package com.evops.rental.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 通用状态流转请求：动作取自状态机配置（application.yml），起止状态不由调用方指定。
 * 新增状态/动作只改配置即可经此接口流转，不改代码。
 */
public class TransitionRequest {

    /** 流转动作（如 ARCHIVE/REVERSE 或配置新增的动作），必填 */
    @NotBlank(message = "流转动作不能为空")
    private String action;

    /** 现场备注/原因（如冲销原因），可选 */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
