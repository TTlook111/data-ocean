package com.dataocean.module.query.service;

import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Field;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 只使用已实际召回的明确商品名与规格声明。精确识别时仍由问数链路核验口径及权限。
 */
final class QueryProductNames {

    /**
     * 已发布库存示例使用“业务编码 是 规格 商品名”的写法，可附明确区分的另一规格。
     */
    private static final String VOLUME =
            "(?:[0-9]+(?:\\.[0-9]+)?|一|二|两)\\s*-?\\s*(?:毫升|ml|升|lit(?:er|re)s?|l)(?![a-z])";

    /**
     * 只识别明确的正文声明，普通数值例句不能用来创建商品映射。
     */
    private static final Pattern DECLARATION = Pattern.compile("(?i)(?<![\\w-])([A-Z][A-Z0-9]*-[A-Z0-9-]+)\\s*是\\s*("
            + VOLUME + ")\\s*([\\p{IsHan}]{2,30})(?:[，,]\\s*与\\s*(" + VOLUME + ")\\s*规格不同)?");

    /**
     * 已由用户直接指定的编码沿用原路径，由正常语义审核处理可能冲突的文字。
     */
    private static final Pattern EXPLICIT_CODE = Pattern.compile("(?i)(?<![\\w-])[A-Z]{2,}-[0-9]");

    /**
     * 规格量与其单位用于核对一升、1000 毫升及 1 L 等明确等量表达。
     */
    private static final Pattern SIZE = Pattern.compile("(?i)" + VOLUME);

    /**
     * 验收口径可识别的全仓库存问法。额外商品、仓库及计算要求走普通规划。
     */
    private static final Pattern SIMPLE_STOCK =
            Pattern.compile("的?(?:还有多少箱?(?:能卖)?|还剩多少箱?(?:能卖)?|多少箱能卖|可售库存(?:还有|还剩|有|是)?多少箱?|库存(?:还有|有|是)?多少箱?)"
                    + "|howmuch(?:couldwestillsell|couldwesell|wasavailableforsale)"
                    + "|howmanycases(?:of)?(?:wereavailableforsale|couldwesell)");

    /**
     * 有额外统计范围时仍可先问清规格，实际查询继续走普通规划。
     */
    private static final Pattern STOCK_WORDS =
            Pattern.compile("(?i)库存|可售|能卖|available for sale|could we (?:still )?sell");

    /**
     * 去除已交给业务日期解析的常见说法，以检查剩余文字是否还有筛选范围。
     */
    private static final Pattern DATE_WORDS =
            Pattern.compile("(?i)大前天|前天|昨天|昨日|今天|今日|明天|后天|(?:上|本|这)(?:周|星期)[一二三四五六日天]|"
                    + "(?:the\\s+)?day before yesterday|two days ago|yesterday|today|"
                    + "\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}(?:\\s*日末)?|\\d{1,2}\\s*[号日](?:末)?|(?:这个|本)月|补充|那款");

    /**
     * 当前发布片段中核实的商品与规格。
     */
    record Match(
            Basis basis,
            Field field,
            String code,
            String name,
            String size,
            String alternative,
            boolean supplied,
            boolean confirmed,
            boolean simpleStock) {}

    /**
     * 用已发布的实际名字和等量规格消歧，识别到多种候选时交给普通语义规划。
     */
    static Match resolve(List<Basis> bases, String question) {
        if (EXPLICIT_CODE.matcher(question).find()) {
            return null;
        }
        String normalized = normalize(question);
        List<BigDecimal> sizes = SIZE.matcher(normalized)
                .results()
                .map(token -> volume(token.group()))
                .distinct()
                .toList();
        var matches = new ArrayList<Match>();
        for (var basis : bases) {
            var fields = basis.labels().entrySet().stream()
                    .filter(entry -> QuerySql.isProductCode(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (fields.size() != 1) {
                // 未确认唯一商品码列时，不让正文文字决定 SQL 标识。
                continue;
            }
            var names = DECLARATION.matcher(basis.text());
            while (names.find()) {
                String name = names.group(3);
                if (!normalized.replaceAll("\\s+", "").contains(normalize(name))) {
                    continue;
                }
                boolean supplied = !sizes.isEmpty();
                boolean confirmed = sizes.size() == 1 && sizes.get(0).compareTo(volume(names.group(2))) == 0;
                String body = DATE_WORDS.matcher(normalized).replaceAll("");
                String rest = SIZE.matcher(body)
                        .replaceAll("")
                        .replaceAll("\\s+", "")
                        .replace(name, "")
                        .replaceAll("\\p{P}+", "");
                matches.add(new Match(
                        basis,
                        fields.get(0),
                        names.group(1),
                        name,
                        names.group(2),
                        names.group(4),
                        supplied,
                        confirmed,
                        SIMPLE_STOCK.matcher(rest).matches()));
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * 发布材料中的定义性商品映射与示例里的普通编号区别处理。
     */
    static boolean declares(Basis basis, String code) {
        return DECLARATION.matcher(basis.text()).results().anyMatch(match -> code.equalsIgnoreCase(match.group(1)));
    }

    /**
     * 识别是否正在问商品的库存可售情况。
     */
    static boolean asksStock(String question) {
        return STOCK_WORDS.matcher(question).find();
    }

    /**
     * 跨语言仅处理可确定等义的通用名称成分，完整名称仍要匹配发布文本。
     */
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\boriginal\\b", "原味")
                .replaceAll("\\boat\\s+milk\\b", "燕麦奶")
                .replace("一升", "1升")
                .replace("二升", "2升")
                .replace("两升", "2升");
    }

    /**
     * 将容量规格转换为毫升后判断等量，保留原文供用户选择。
     */
    private static BigDecimal volume(String label) {
        String text = normalize(label).replaceAll("[\\s-]", "");
        int boundary = text.indexOf("毫升");
        boolean milliliters = boundary >= 0 || text.endsWith("ml");
        String number = text.replaceAll("(?:毫升|ml|升|lit(?:er|re)s?|l)$", "")
                .replace("一", "1")
                .replace("二", "2")
                .replace("两", "2");
        return new BigDecimal(number)
                .multiply(milliliters ? BigDecimal.ONE : BigDecimal.valueOf(1000))
                .stripTrailingZeros();
    }

    /**
     * 商品解析依赖当次固定发布证据。
     */
    private QueryProductNames() {}
}
