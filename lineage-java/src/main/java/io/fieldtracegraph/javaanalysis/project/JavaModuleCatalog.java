package io.fieldtracegraph.javaanalysis.project;

import io.fieldtracegraph.core.model.SourceFileRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 作用：识别 Maven 或 Gradle 的模块边界。
// 使用场景：多模块项目中判定源码目录、包名与模块归属。

/**
 * Immutable module catalog derived from the scanned source files. It maps a
 * module id to the language level used for that module.
 */
public final class JavaModuleCatalog {
    private final Map<String, String> languageLevelByModule;

    // 作用：保存模块语言级别映射；逻辑：直接持有调用方传入的映射。
    private JavaModuleCatalog(Map<String, String> languageLevelByModule) {
        this.languageLevelByModule = languageLevelByModule;
    }

    // 作用：从源码文件构建模块目录；逻辑：按模块首次出现的语言级别登记，并复制为不可变映射。
    public static JavaModuleCatalog build(List<SourceFileRecord> files) {
        Map<String, String> levels = new LinkedHashMap<>();
        for (SourceFileRecord file : files) {
            levels.putIfAbsent(file.moduleId(), file.languageLevel());
        }
        return new JavaModuleCatalog(Map.copyOf(levels));
    }

    // 作用：查询模块语言级别；逻辑：按模块标识返回登记值，未知模块返回空。
    public String languageLevel(String moduleId) {
        return languageLevelByModule.get(moduleId);
    }
}
