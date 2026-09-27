package com.evops.telematics;

/**
 * CSV 结构解析失败（引号未闭合、闭合后跟随非法字符等），携带出错物理行号。
 */
public class CsvFormatException extends RuntimeException {

    private final int lineNo;

    public CsvFormatException(int lineNo, String message) {
        super(message);
        this.lineNo = lineNo;
    }

    public int getLineNo() { return lineNo; }
}
