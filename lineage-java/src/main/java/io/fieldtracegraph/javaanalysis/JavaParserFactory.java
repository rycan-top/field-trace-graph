package io.fieldtracegraph.javaanalysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;

// 作用：按语言级别创建 JavaParser。
// 使用场景：--language-level 指定或自动推断语言级别时。

public final class JavaParserFactory {
    // 作用：创建解析器；逻辑：按语言级别设置解析配置后构造 JavaParser。
    public JavaParser create(String languageLevel) {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(resolve(languageLevel));
        return new JavaParser(configuration);
    }

    // 作用：解析语言级别；逻辑：规范化版本号并映射到支持的 JavaParser 级别，未知级别报错。
    private ParserConfiguration.LanguageLevel resolve(String languageLevel) {
        if (languageLevel == null || languageLevel.isBlank()) {
            throw new IllegalArgumentException("UNSUPPORTED_LANGUAGE: missing language level");
        }
        String normalized = normalize(languageLevel);
        return switch (normalized) {
            case "1", "2", "3", "4", "5" -> ParserConfiguration.LanguageLevel.JAVA_5;
            case "6" -> ParserConfiguration.LanguageLevel.JAVA_6;
            case "7" -> ParserConfiguration.LanguageLevel.JAVA_7;
            case "8" -> ParserConfiguration.LanguageLevel.JAVA_8;
            case "9" -> ParserConfiguration.LanguageLevel.JAVA_9;
            case "10" -> ParserConfiguration.LanguageLevel.JAVA_10;
            case "11" -> ParserConfiguration.LanguageLevel.JAVA_11;
            case "12" -> ParserConfiguration.LanguageLevel.JAVA_12;
            case "13" -> ParserConfiguration.LanguageLevel.JAVA_13;
            case "14" -> ParserConfiguration.LanguageLevel.JAVA_14;
            case "15" -> ParserConfiguration.LanguageLevel.JAVA_15;
            case "16" -> ParserConfiguration.LanguageLevel.JAVA_16;
            case "17" -> ParserConfiguration.LanguageLevel.JAVA_17;
            case "18" -> ParserConfiguration.LanguageLevel.JAVA_18;
            case "21" -> ParserConfiguration.LanguageLevel.JAVA_21;
            default -> throw new IllegalArgumentException("UNSUPPORTED_LANGUAGE: " + languageLevel);
        };
    }

    // 作用：规范化版本号；逻辑：去掉点号及其前缀，仅保留主版本。
    private String normalize(String languageLevel) {
        String normalized = languageLevel.trim();
        int dot = normalized.indexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }
}
