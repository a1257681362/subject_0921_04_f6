package com.evops.telematics;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDate;

/**
 * 导出文件归档记录：一业务日可多次导出（补发），每次生成新文件并留痕。
 * 文件按业务日分目录落盘（file_path 唯一）。
 */
@TableName("t_telematics_export_file")
public class TelematicsExportFile extends BizEntity {

    /** 导出覆盖的业务日 */
    private LocalDate bizDate;

    private String fileName;

    /** 归档落盘路径（按业务日 yyyyMMdd 分目录） */
    private String filePath;

    /** 数据行数（不含表头） */
    private Integer recordCount;

    /** 文件字符集（与对方规范一致） */
    private String charset;

    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
