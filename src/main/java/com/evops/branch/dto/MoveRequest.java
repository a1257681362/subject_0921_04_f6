package com.evops.branch.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 子树迁移请求：被迁节点带着子孙整体搬到新父节点下，0 = 迁为根节点。
 */
public class MoveRequest {

    @NotNull(message = "目标父节点不能为空（0 表示迁为根节点）")
    @Min(value = 0, message = "目标父节点 id 不能为负数")
    private Long newParentId;

    /** 迁移原因（可选，写入事件留痕） */
    @Size(max = 400, message = "迁移原因长度不能超过 400")
    private String reason;

    public Long getNewParentId() { return newParentId; }
    public void setNewParentId(Long newParentId) { this.newParentId = newParentId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
