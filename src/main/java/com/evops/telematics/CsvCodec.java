package com.evops.telematics;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 编解码（与车载定位平台文件往来规范一致）：
 * <ul>
 *   <li>行尾 CRLF，首行表头；</li>
 *   <li>字段含 逗号/双引号/回车/换行 时双引号包围，字段内双引号双写；</li>
 *   <li>null 写为空字段（两分隔符间无内容），空串写为 "" —— 空值与空串可区分；</li>
 *   <li>解析按记录起始物理行报行号（引号内换行时一条记录可跨多行）。</li>
 * </ul>
 */
public final class CsvCodec {

    public static final String LINE_SEP = "\r\n";

    private CsvCodec() {}

    /** 编码一条记录为一行（不含行尾）。 */
    public static String encodeRecord(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(encodeField(fields.get(i)));
        }
        return sb.toString();
    }

    /** 单字段编码：null -> 空字段；空串 -> ""；含特殊字符 -> 引号包围并双写引号。 */
    public static String encodeField(String field) {
        if (field == null) {
            return "";
        }
        boolean needQuote = field.isEmpty()
                || field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0;
        if (!needQuote) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    /** 解析整个文件内容，返回记录列表（空行跳过）。结构非法抛 CsvFormatException（带行号）。 */
    public static List<Row> parse(String content) {
        List<Row> rows = new ArrayList<>();
        Cursor cursor = new Cursor(content);
        while (cursor.pos < cursor.len) {
            int recordLine = cursor.line;
            List<String> fields = new ArrayList<>();
            while (true) {
                fields.add(parseField(cursor, recordLine));
                if (cursor.at(',')) {
                    cursor.pos++;
                    continue;
                }
                // 行尾：\r\n / \n / 单独 \r 均可识别
                if (cursor.at('\r')) {
                    cursor.pos++;
                    if (cursor.at('\n')) {
                        cursor.pos++;
                    }
                    cursor.line++;
                } else if (cursor.at('\n')) {
                    cursor.pos++;
                    cursor.line++;
                }
                break;
            }
            // 空行（单个空字段）跳过，不计入记录
            if (fields.size() == 1 && fields.get(0) == null) {
                continue;
            }
            rows.add(new Row(recordLine, fields));
        }
        return rows;
    }

    private static String parseField(Cursor cursor, int recordLine) {
        String content = cursor.content;
        if (cursor.at('"')) {
            // 引号字段："" 为字面引号，引号内可含换行（行号随行数推进）
            cursor.pos++;
            StringBuilder sb = new StringBuilder();
            boolean closed = false;
            while (cursor.pos < cursor.len) {
                char c = content.charAt(cursor.pos);
                if (c == '"') {
                    if (cursor.pos + 1 < cursor.len && content.charAt(cursor.pos + 1) == '"') {
                        sb.append('"');
                        cursor.pos += 2;
                    } else {
                        cursor.pos++;
                        closed = true;
                        break;
                    }
                } else {
                    if (c == '\n') {
                        cursor.line++;
                    }
                    sb.append(c);
                    cursor.pos++;
                }
            }
            if (!closed) {
                throw new CsvFormatException(recordLine, "引号未闭合");
            }
            if (cursor.pos < cursor.len && !cursor.at(',') && !cursor.at('\r') && !cursor.at('\n')) {
                throw new CsvFormatException(cursor.line, "引号闭合后出现非法字符");
            }
            return sb.toString();
        }
        // 非引号字段：读到分隔符或行尾；空内容即 null（与 "" 空串区分）
        int from = cursor.pos;
        while (cursor.pos < cursor.len && !cursor.at(',') && !cursor.at('\r') && !cursor.at('\n')) {
            cursor.pos++;
        }
        String raw = content.substring(from, cursor.pos);
        return raw.isEmpty() ? null : raw;
    }

    /** 解析游标：位置 + 当前物理行号。 */
    private static final class Cursor {
        private final String content;
        private final int len;
        private int pos;
        private int line = 1;

        private Cursor(String content) {
            this.content = content;
            this.len = content.length();
        }

        private boolean at(char c) {
            return pos < len && content.charAt(pos) == c;
        }
    }

    /** 一条记录：起始物理行号（1 起，含表头）+ 字段值（null 与空串可区分）。 */
    public static final class Row {
        private final int lineNo;
        private final List<String> fields;

        public Row(int lineNo, List<String> fields) {
            this.lineNo = lineNo;
            this.fields = fields;
        }

        public int getLineNo() { return lineNo; }
        public List<String> getFields() { return fields; }
    }
}
