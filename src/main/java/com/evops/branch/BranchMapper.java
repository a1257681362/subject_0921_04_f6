package com.evops.branch;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BranchMapper extends BaseMapper<Branch> {

    String NODE_COLUMNS = "b.id, b.branch_code, b.name, b.parent_id, b.sort_order, c.depth";

    /** 直接子节点：闭包 depth = 1，按同级排序配置（sort_order, id）返回。 */
    @Select("SELECT " + NODE_COLUMNS
            + " FROM t_branch b JOIN t_branch_closure c ON c.descendant_id = b.id"
            + " WHERE c.ancestor_id = #{rootId} AND c.depth = 1"
            + " ORDER BY b.sort_order, b.id")
    List<BranchNode> selectChildren(@Param("rootId") Long rootId);

    /**
     * 任意层级后代（可选最大深度）：按 (ancestor_id) 单索引等值扫描，
     * 性能与树深无关；按深度、同级排序配置返回。
     */
    @Select("SELECT " + NODE_COLUMNS
            + " FROM t_branch b JOIN t_branch_closure c ON c.descendant_id = b.id"
            + " WHERE c.ancestor_id = #{rootId} AND c.depth > 0"
            + " AND (#{maxDepth} IS NULL OR c.depth <= #{maxDepth})"
            + " ORDER BY c.depth, b.sort_order, b.id")
    List<BranchNode> selectSubtree(@Param("rootId") Long rootId, @Param("maxDepth") Integer maxDepth);

    /** 到根路径（不含自身），根在前。 */
    @Select("SELECT " + NODE_COLUMNS
            + " FROM t_branch b JOIN t_branch_closure c ON c.ancestor_id = b.id"
            + " WHERE c.descendant_id = #{nodeId} AND c.depth > 0"
            + " ORDER BY c.depth DESC")
    List<BranchNode> selectAncestors(@Param("nodeId") Long nodeId);
}
