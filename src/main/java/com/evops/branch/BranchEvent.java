package com.evops.branch;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 网点树变更留痕：建点 / 子树迁移 / 同级排序。
 * 闭包表是派生数据不随行留痕，操作审计以本表为准（试点观察期的审计依据）。
 */
@TableName("t_branch_event")
public class BranchEvent extends BizEntity {

    /** 发生变更的节点 id（迁移时为被迁子树的根） */
    private Long branchId;

    /** CREATE / MOVE / SORT */
    private String eventType;

    /** 变更明细（如 父节点 0 -> 5、排序 0 -> 3） */
    private String detail;

    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
