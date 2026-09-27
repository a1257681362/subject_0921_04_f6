package com.evops.branch;

import com.evops.branch.dto.BranchCreateRequest;
import com.evops.branch.dto.MoveRequest;
import com.evops.branch.dto.SortRequest;
import com.evops.common.ApiResponse;
import com.evops.common.BizException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/branches")
public class BranchController {

    private final BranchService branchService;

    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    /** 新建网点节点（parentId 缺省 0 = 根节点） */
    @PostMapping
    public ApiResponse<Branch> create(@Valid @RequestBody BranchCreateRequest request) {
        return ApiResponse.ok(branchService.create(request));
    }

    /** 子树整体迁移（原子）：节点带子孙搬到新父节点下，0 = 迁为根节点 */
    @PostMapping("/{id}/move")
    public ApiResponse<Branch> move(@PathVariable Long id,
                                    @Valid @RequestBody MoveRequest request) {
        return ApiResponse.ok(branchService.move(id, request));
    }

    /** 同级排序配置 */
    @PutMapping("/{id}/sort")
    public ApiResponse<Branch> updateSort(@PathVariable Long id,
                                          @Valid @RequestBody SortRequest request) {
        return ApiResponse.ok(branchService.updateSort(id, request));
    }

    /** 节点详情（运维诊断，不过灰度闸） */
    @GetMapping("/{id}")
    public ApiResponse<Branch> detail(@PathVariable Long id) {
        return ApiResponse.ok(branchService.detail(id));
    }

    /** 直接子节点，按同级排序配置（调度查询新链路，灰度闸内） */
    @GetMapping("/{id}/children")
    public ApiResponse<List<BranchNode>> children(@PathVariable Long id) {
        return ApiResponse.ok(branchService.children(id));
    }

    /** 任意层级后代，可选 maxDepth（调度查询新链路，灰度闸内） */
    @GetMapping("/{id}/subtree")
    public ApiResponse<List<BranchNode>> subtree(@PathVariable Long id,
                                                 @RequestParam(required = false) Integer maxDepth) {
        return ApiResponse.ok(branchService.subtree(id, maxDepth));
    }

    /** 到根路径，根在前、不含自身（调度查询新链路，灰度闸内） */
    @GetMapping("/{id}/path")
    public ApiResponse<List<BranchNode>> path(@PathVariable Long id) {
        return ApiResponse.ok(branchService.path(id));
    }

    /**
     * 网点为全量留痕记录，不允许物理删除；
     * 层级调整请使用 /move 迁移接口。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        throw new BizException("网点为全量留痕记录，禁止物理删除；层级调整请使用迁移接口");
    }
}
