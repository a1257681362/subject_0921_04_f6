package com.evops.branch.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 新建网点节点请求。parentId 缺省 0 = 根节点；sortOrder 缺省 0。
 */
public class BranchCreateRequest {

    @NotBlank(message = "网点编码不能为空")
    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    @NotBlank(message = "网点名称不能为空")
    @Size(max = 64, message = "网点名称长度不能超过 64")
    private String name;

    /** 父节点 id，缺省 0 = 根节点 */
    @Min(value = 0, message = "父节点 id 不能为负数")
    private Long parentId;

    /** 同级排序配置，缺省 0 */
    private Integer sortOrder;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
