package io.fieldtracegraph.javaanalysis.project;

import java.util.List;
import java.util.Set;

// 作用：记录 class 文件的元数据。
// 使用场景：不加载类即可确认外部类型的字段与方法。

/**
 * Structural metadata read from one class file. It intentionally contains no
 * executable code, only names, descriptors, flags and class-level annotations.
 */
public record ClassFileMetadata(
        String name,
        int accessFlags,
        String superName,
        List<String> interfaces,
        Set<String> fieldNames,
        List<MethodMetadata> methods,
        Set<String> annotationDescriptors
) {
    // 作用：规范化 class 元数据；逻辑：把可能为空的接口、字段、方法与注解集合替换为不可变空集合或副本。
    public ClassFileMetadata {
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        fieldNames = fieldNames == null ? Set.of() : Set.copyOf(fieldNames);
        methods = methods == null ? List.of() : List.copyOf(methods);
        annotationDescriptors = annotationDescriptors == null
                ? Set.of()
                : Set.copyOf(annotationDescriptors);
    }

    // 作用：判断类中是否存在指定方法；逻辑：按方法名匹配，实参数量非负时同时校验参数个数。
    public boolean hasMethod(String methodName, int argumentCount) {
        return methods.stream().anyMatch(method ->
                method.name().equals(methodName)
                        && (argumentCount < 0 || method.argumentCount() == argumentCount));
    }

    // 作用：判断类上是否存在指定注解；逻辑：同时接受描述符和简单名，逐个比较规范化后的注解名。
    public boolean hasAnnotation(String simpleNameOrDescriptor) {
        if (simpleNameOrDescriptor == null || simpleNameOrDescriptor.isBlank()) {
            return false;
        }
        String expected = simpleNameOrDescriptor.trim();
        for (String descriptor : annotationDescriptors) {
            String simple = simpleAnnotationName(descriptor);
            if (descriptor.equals(expected) || simple.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    // 作用：把注解描述符转换为简单名；逻辑：去掉 L 前缀与分号，截取最后一个斜杠后的名称并移除内部类前缀。
    public static String simpleAnnotationName(String descriptor) {
        String value = descriptor;
        if (value.startsWith("L") && value.endsWith(";")) {
            value = value.substring(1, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        if (value.startsWith("$")) {
            value = value.substring(1);
        }
        return value;
    }

    public record MethodMetadata(String name, String descriptor, int accessFlags) {
        // 作用：计算方法描述符中的参数个数；逻辑：委托给描述符解析函数。
        public int argumentCount() {
            return parameterCount(descriptor);
        }

        // 作用：解析方法描述符的参数数量；逻辑：跳过数组维度并整体跨过对象类型，逐个统计参数。
        static int parameterCount(String descriptor) {
            if (descriptor == null || descriptor.length() < 2 || descriptor.charAt(0) != '(') {
                return 0;
            }
            int count = 0;
            int index = 1;
            while (index < descriptor.length() && descriptor.charAt(index) != ')') {
                char current = descriptor.charAt(index);
                if (current == '[') {
                    index++;
                    continue;
                }
                if (current == 'L') {
                    int end = descriptor.indexOf(';', index);
                    if (end < 0) {
                        break;
                    }
                    index = end + 1;
                } else {
                    index++;
                }
                count++;
            }
            return count;
        }
    }
}
