package com.evops.telematics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.Charset;

/**
 * 车载定位平台文件往来配置。
 */
@Component
@ConfigurationProperties(prefix = "evops.telematics")
public class TelematicsProperties {

    /** 导出文件归档根目录（按业务日 yyyyMMdd 分目录落盘） */
    private String exportDir = "data/telematics/export";

    /** 文件往来字符集（与对方规范一致，如对方要求 GBK 改此配置） */
    private String charset = "UTF-8";

    /** 启动即校验字符集可用，避免上线后导出才发现编码不支持 */
    @PostConstruct
    public void validate() {
        if (!Charset.isSupported(charset)) {
            throw new IllegalStateException("evops.telematics.charset 不支持：" + charset);
        }
    }

    public Charset charsetObj() {
        return Charset.forName(charset);
    }

    public String getExportDir() { return exportDir; }
    public void setExportDir(String exportDir) { this.exportDir = exportDir; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
