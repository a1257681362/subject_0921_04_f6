package com.evops.branch;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 网点节点。全量留痕，禁止物理删除；parent_id = 0 表示根节点（允许森林）。
 */
@TableName("t_branch")
public class Branch extends BizEntity {

    /** 根节点的 parent_id 取值 */
    public static final long ROOT_PARENT_ID = 0L;

    /** 网点编码（库层唯一） */
    private String branchCode;

    /** 网点名称 */
    private String name;

    /** 父节点 id，0 = 根节点 */
    private Long parentId;

    /** 同级排序配置，小的在前，同值按 id */
    private Integer sortOrder = 0;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
