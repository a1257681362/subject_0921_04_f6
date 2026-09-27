package com.evops.rental;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 客户姓名拼音工具（pinyin4j）。
 *
 * 容错约定（入库与检索入参走同一套规范化，保证可对上）：
 * - NFKC 归一：全角字母/数字/空格转半角（如 ＺＳ -> zs、１３８ -> 138）；
 * - 统一转小写，做到大小写不敏感；
 * - 丢弃空白与标点（含中点、间隔号），只保留字母/数字/汉字，做到空格、点号容错；
 * - 多音字取拼音库返回的首个读音，全拼与首字母用同一读音，结果稳定。
 *
 * 产出：
 * - {@link #normalize(String)} 规范化原文（汉字保留），支持中文/外文字母直查；
 * - {@link #fullPinyin(String)} 逐字全拼、无分隔符小写串（如 "张三丰" -> "zhangsanfeng"）；
 * - {@link #initials(String)} 逐字拼音首字母（如 "张三丰" -> "zsf"）。
 * 非汉字字符在全拼/首字母中原样保留（如 "张a三" 全拼 "zhangasan"、首字母 "zas"）。
 */
public final class PinyinNames {

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinNames() {
    }

    /** 规范化：NFKC 全半角归一 + 小写 + 去空白标点（只留字母/数字/汉字）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            // 汉字归为 Letter，会一并保留；空白与标点被丢弃
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 逐字全拼（多音字取首个读音，非汉字原样保留）。 */
    public static String fullPinyin(String name) {
        String normalized = normalize(name);
        StringBuilder sb = new StringBuilder(normalized.length() * 2);
        for (int i = 0; i < normalized.length(); i++) {
            sb.append(firstReading(normalized.charAt(i)));
        }
        return sb.toString();
    }

    /** 逐字首字母（多音字取首个读音的首字母，非汉字取其本身）。 */
    public static String initials(String name) {
        String normalized = normalize(name);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            String reading = firstReading(normalized.charAt(i));
            if (!reading.isEmpty()) {
                sb.append(reading.charAt(0));
            }
        }
        return sb.toString();
    }

    /**
     * 取单字首个拼音读音；非汉字或拼音库未收录字原样返回小写字符。
     * 输出格式为静态固定合法组合，异常理论上不会发生，出现即包装为非法状态。
     */
    private static String firstReading(char c) {
        try {
            String[] readings = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
            if (readings != null && readings.length > 0) {
                return readings[0];
            }
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            throw new IllegalStateException("拼音输出格式非法", e);
        }
        return String.valueOf(c);
    }
}
