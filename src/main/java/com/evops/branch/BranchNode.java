package com.evops.branch;

/**
 * 树查询返回节点：网点字段 + 相对查询根的深度（depth）。
 */
public class BranchNode {

    private Long id;
    private String branchCode;
    private String name;
    private Long parentId;
    private Integer sortOrder;

    /** 相对查询根的深度：1 = 直接子节点，以此类推 */
    private Integer depth;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getDepth() { return depth; }
    public void setDepth(Integer depth) { this.depth = depth; }
}
