package com.evops.telematics;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 设备回执导入记录。file_hash 唯一：同一文件重复导入幂等，直接返回首次结果
 * （duplicate=true）。全或无：任一行解析失败整单 FAILED，error_detail 带行号，
 * 业务行零写入。
 */
@TableName("t_telematics_import_file")
public class TelematicsImportFile extends BizEntity {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private String fileName;

    /** 文件内容 SHA-256，重复导入幂等键 */
    private String fileHash;

    private String charset;

    /** SUCCESS / FAILED */
    private String status;

    /** 数据行数（不含表头） */
    private Integer totalLines;

    private Integer successLines;

    /** 解析失败明细：第 N 行：原因（逐行一条） */
    private String errorDetail;

    /** 非库列：本次请求命中重复文件（同哈希已导入过） */
    @TableField(exist = false)
    private boolean duplicate;

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalLines() { return totalLines; }
    public void setTotalLines(Integer totalLines) { this.totalLines = totalLines; }
    public Integer getSuccessLines() { return successLines; }
    public void setSuccessLines(Integer successLines) { this.successLines = successLines; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public boolean isDuplicate() { return duplicate; }
    public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }
}
