package com.evops.branch;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 闭包表 t_branch_closure 维护。闭包行是派生索引数据：
 * 建点时写自闭环并继承父节点祖先链；子树迁移时整体断开旧外部链路、重建新祖先链。
 */
@Mapper
public interface BranchClosureMapper {

    /** 自闭环：每个节点都是自己的 0 级祖先。 */
    @Insert("INSERT INTO t_branch_closure (ancestor_id, descendant_id, depth)"
            + " VALUES (#{id}, #{id}, 0)")
    int insertSelfLink(@Param("id") Long id);

    /** 新节点继承父节点的全部祖先链（深度 +1）。 */
    @Insert("INSERT INTO t_branch_closure (ancestor_id, descendant_id, depth)"
            + " SELECT ancestor_id, #{descendantId}, depth + 1 FROM t_branch_closure"
            + " WHERE descendant_id = #{parentId}")
    int insertAncestorLinks(@Param("descendantId") Long descendantId, @Param("parentId") Long parentId);

    /** 链路是否存在（迁移防成环 / 灰度子树判定）。 */
    @Select("SELECT COUNT(*) FROM t_branch_closure"
            + " WHERE ancestor_id = #{ancestorId} AND descendant_id = #{descendantId}")
    long countLink(@Param("ancestorId") Long ancestorId, @Param("descendantId") Long descendantId);

    /**
     * 子树迁移第 1 步：删除被迁子树与旧外部祖先之间的全部链路（旧链路清理干净），
     * 子树内部链路保留。子查询包一层派生表，兼容 MySQL 同表删查限制。
     */
    @Delete("DELETE FROM t_branch_closure"
            + " WHERE descendant_id IN (SELECT descendant_id FROM ("
            + "     SELECT descendant_id FROM t_branch_closure WHERE ancestor_id = #{nodeId}) sub_tree)"
            + " AND ancestor_id NOT IN (SELECT descendant_id FROM ("
            + "     SELECT descendant_id FROM t_branch_closure WHERE ancestor_id = #{nodeId}) sub_tree2)")
    int deleteExternalLinks(@Param("nodeId") Long nodeId);

    /**
     * 子树迁移第 2 步：新父节点的祖先链（含其自闭环）× 被迁子树，深度重新计算。
     */
    @Insert("INSERT INTO t_branch_closure (ancestor_id, descendant_id, depth)"
            + " SELECT a.ancestor_id, d.descendant_id, a.depth + 1 + d.depth"
            + " FROM t_branch_closure a"
            + " JOIN t_branch_closure d ON d.ancestor_id = #{nodeId}"
            + " WHERE a.descendant_id = #{newParentId}")
    int insertLinksUnder(@Param("nodeId") Long nodeId, @Param("newParentId") Long newParentId);
}
