package com.dataocean.module.knowledge.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 按业务主题组织文档，不承担 Python 的 token 切片职责。保留全部原文及来源标记。 */
public final class KnowledgeDocumentPartitioner {
    private KnowledgeDocumentPartitioner() {}
    public record Part(String title, String content) {}
    public static List<Part> split(String content) {
        var sections = Pattern.compile("(?m)(?=^## )").split(content);
        var result = new ArrayList<Part>();
        var prefix = new StringBuilder();
        for (String section : sections) {
            if (!section.contains("<!-- dataocean-fact:")) {
                if (result.isEmpty()) prefix.append(section);
                else {
                    Part previous = result.remove(result.size() - 1);
                    result.add(new Part(previous.title(), previous.content() + section));
                }
                continue;
            }
            String heading = section.lines().findFirst().orElse("知识目录").replaceFirst("^##\\s*", "");
            result.add(new Part(heading, prefix + section));
            prefix.setLength(0);
        }
        if (result.isEmpty()) return List.of(new Part("知识目录", content));
        return List.copyOf(result);
    }
}
