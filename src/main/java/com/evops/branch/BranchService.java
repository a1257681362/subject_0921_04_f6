package com.evops.branch;

import com.evops.branch.dto.BranchCreateRequest;
import com.evops.branch.dto.MoveRequest;
import com.evops.branch.dto.SortRequest;
import com.evops.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 网点树主流程（闭包表）。
 *
 * 规则：
 * - 建点/子树迁移/排序配置的多表写入共用一个事务，中途失败全部回退；
 * - 子树迁移原子完成：防成环校验 -> 删子树外部旧链路（内部链路保留）->
 *   接新祖先链 -> 改 parent_id（乐观锁）-> 写 MOVE 事件；
 * - 闭包表为派生索引数据，不随行业务四要素，变更留痕在 t_branch_event；
 * - 网点全量留痕，不提供物理删除；
 * - 调度查询读路径（children/subtree/path）过灰度闸 BranchGrayGate。
 */
@Service
public class BranchService {

    private final BranchMapper branchMapper;
    private final BranchClosureMapper closureMapper;
    private final BranchEventMapper eventMapper;
    private final BranchGrayGate grayGate;

    public BranchService(BranchMapper branchMapper, BranchClosureMapper closureMapper,
                         BranchEventMapper eventMapper, BranchGrayGate grayGate) {
        this.branchMapper = branchMapper;
        this.closureMapper = closureMapper;
        this.eventMapper = eventMapper;
        this.grayGate = grayGate;
    }

    /** 建点：节点 + 自闭环 + 继承父节点祖先链 + CREATE 事件，同一事务。 */
    @Transactional(rollbackFor = Exception.class)
    public Branch create(BranchCreateRequest request) {
        long parentId = request.getParentId() != null ? request.getParentId() : Branch.ROOT_PARENT_ID;
        if (parentId != Branch.ROOT_PARENT_ID && branchMapper.selectById(parentId) == null) {
            throw new BizException("父节点不存在：" + parentId);
        }

        Branch branch = new Branch();
        branch.setBranchCode(request.getBranchCode().trim());
        branch.setName(request.getName().trim());
        branch.setParentId(parentId);
        branch.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        branchMapper.insert(branch);   // branch_code 库层唯一约束兜底重复提交

        closureMapper.insertSelfLink(branch.getId());
        if (parentId != Branch.ROOT_PARENT_ID) {
            closureMapper.insertAncestorLinks(branch.getId(), parentId);
        }

        recordEvent(branch.getId(), EventType.CREATE,
                "新建网点：" + branch.getName() + "（编码 " + branch.getBranchCode()
                        + "），父节点：" + parentId + "，同级排序：" + branch.getSortOrder());
        return branch;
    }

    /**
     * 子树整体迁移（原子）：节点带着子孙整体搬到新父节点下（0 = 迁为根节点）。
     * 旧外部链路删干净、子树内部链路保留、新祖先链重建，全部在同一事务，
     * 中途失败整体回滚，不留半截链路。
     */
    @Transactional(rollbackFor = Exception.class)
    public Branch move(Long nodeId, MoveRequest request) {
        Long newParentId = request.getNewParentId();
        Branch node = requireBranch(nodeId);
        if (newParentId.equals(nodeId)) {
            throw new BizException("不能把节点挂到自身下：" + nodeId);
        }
        if (newParentId.equals(node.getParentId())) {
            throw new BizException("节点已在目标父节点下，无需迁移：" + nodeId);
        }
        if (newParentId != Branch.ROOT_PARENT_ID) {
            if (branchMapper.selectById(newParentId) == null) {
                throw new BizException("目标父节点不存在：" + newParentId);
            }
            // 防成环：目标父节点不能落在被迁子树内
            if (closureMapper.countLink(nodeId, newParentId) > 0) {
                throw new BizException("目标父节点位于被迁子树内，迁移会成环，已拒绝：" + newParentId);
            }
        }

        long oldParentId = node.getParentId();
        // 1. 旧链路清理干净：断开子树与旧外部祖先的全部链路（子树内部链路保留）
        closureMapper.deleteExternalLinks(nodeId);
        // 2. 接新链路：新父祖先链 × 子树（迁为根节点则无需新链路）
        if (newParentId != Branch.ROOT_PARENT_ID) {
            closureMapper.insertLinksUnder(nodeId, newParentId);
        }
        // 3. 改父指针（乐观锁兜底并发搬家）
        node.setParentId(newParentId);
        int updated = branchMapper.updateById(node);
        if (updated == 0) {
            throw new BizException("节点已被并发修改，请刷新后重试：" + nodeId);
        }

        recordEvent(nodeId, EventType.MOVE,
                "子树迁移：父节点 " + oldParentId + " -> " + newParentId
                        + (request.getReason() != null && !request.getReason().trim().isEmpty()
                        ? "，原因：" + request.getReason().trim() : ""));
        return branchMapper.selectById(nodeId);
    }

    /** 同级排序配置：sort_order 小的在前，同值按 id。 */
    @Transactional(rollbackFor = Exception.class)
    public Branch updateSort(Long nodeId, SortRequest request) {
        Branch node = requireBranch(nodeId);
        int oldSort = node.getSortOrder();
        node.setSortOrder(request.getSortOrder());
        int updated = branchMapper.updateById(node);
        if (updated == 0) {
            throw new BizException("节点已被并发修改，请刷新后重试：" + nodeId);
        }
        recordEvent(nodeId, EventType.SORT, "同级排序：" + oldSort + " -> " + request.getSortOrder());
        return branchMapper.selectById(nodeId);
    }

    /** 节点详情（运维诊断，不过灰度闸）。 */
    public Branch detail(Long nodeId) {
        return requireBranch(nodeId);
    }

    /** 直接子节点（灰度闸内），按同级排序配置返回。 */
    public List<BranchNode> children(Long nodeId) {
        requireBranch(nodeId);
        grayGate.assertOpen(nodeId);
        return branchMapper.selectChildren(nodeId);
    }

    /** 任意层级后代（灰度闸内），可选最大深度；单索引等值扫描，性能与树深无关。 */
    public List<BranchNode> subtree(Long nodeId, Integer maxDepth) {
        requireBranch(nodeId);
        grayGate.assertOpen(nodeId);
        if (maxDepth != null && maxDepth < 1) {
            throw new BizException("maxDepth 必须 >= 1");
        }
        return branchMapper.selectSubtree(nodeId, maxDepth);
    }

    /** 到根路径（灰度闸内），根在前、不含自身。 */
    public List<BranchNode> path(Long nodeId) {
        requireBranch(nodeId);
        grayGate.assertOpen(nodeId);
        return branchMapper.selectAncestors(nodeId);
    }

    // ---- 内部辅助 ----

    private Branch requireBranch(Long nodeId) {
        Branch branch = branchMapper.selectById(nodeId);
        if (branch == null) {
            throw new BizException("网点不存在：" + nodeId);
        }
        return branch;
    }

    private void recordEvent(Long branchId, String eventType, String detail) {
        BranchEvent event = new BranchEvent();
        event.setBranchId(branchId);
        event.setEventType(eventType);
        event.setDetail(detail);
        eventMapper.insert(event);
    }

    /** 事件类型常量 */
    public static final class EventType {
        public static final String CREATE = "CREATE";
        public static final String MOVE = "MOVE";
        public static final String SORT = "SORT";

        private EventType() {}
    }
}
