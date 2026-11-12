package io.fieldtracegraph.javaanalysis.project;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 作用：直接读取 class 文件的元数据。
// 使用场景：补充外部依赖类型信息，避免执行依赖代码。

/**
 * Minimal class-file reader for lineage metadata. It deliberately does not
 * verify bytecode, load classes, or execute static initializers.
 */
public final class ClassFileReader {
    private static final int MAGIC = 0xCAFEBABE;

    // 作用：从字节流读取 class 元数据；逻辑：依次解析常量池、类头、接口、字段、方法与类级注解，不加载或执行类。
    public ClassFileMetadata read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != MAGIC) {
            throw new IOException("Not a Java class file");
        }
        data.readUnsignedShort();
        data.readUnsignedShort();
        ConstantPool pool = ConstantPool.read(data);
        int accessFlags = data.readUnsignedShort();
        int thisClass = data.readUnsignedShort();
        int superClass = data.readUnsignedShort();
        int interfaceCount = data.readUnsignedShort();
        List<String> interfaces = new ArrayList<>(interfaceCount);
        for (int index = 0; index < interfaceCount; index++) {
            interfaces.add(pool.className(data.readUnsignedShort()));
        }
        String name = pool.className(thisClass);
        String superName = superClass == 0 ? null : pool.className(superClass);

        Set<String> fields = new LinkedHashSet<>();
        int fieldCount = data.readUnsignedShort();
        for (int index = 0; index < fieldCount; index++) {
            data.readUnsignedShort();
            fields.add(pool.utf8(data.readUnsignedShort()));
            data.readUnsignedShort();
            skipAttributes(data);
        }

        List<ClassFileMetadata.MethodMetadata> methods = new ArrayList<>();
        int methodCount = data.readUnsignedShort();
        for (int index = 0; index < methodCount; index++) {
            int methodAccess = data.readUnsignedShort();
            String methodName = pool.utf8(data.readUnsignedShort());
            String descriptor = pool.utf8(data.readUnsignedShort());
            methods.add(new ClassFileMetadata.MethodMetadata(methodName, descriptor, methodAccess));
            skipAttributes(data);
        }

        Set<String> annotations = new LinkedHashSet<>();
        int attributeCount = data.readUnsignedShort();
        for (int index = 0; index < attributeCount; index++) {
            String attributeName = pool.utf8(data.readUnsignedShort());
            int length = data.readInt();
            if (!"RuntimeVisibleAnnotations".equals(attributeName)
                    && !"RuntimeInvisibleAnnotations".equals(attributeName)) {
                skipFully(data, length);
                continue;
            }
            int annotationCount = data.readUnsignedShort();
            for (int annotationIndex = 0; annotationIndex < annotationCount; annotationIndex++) {
                annotations.add(pool.utf8(data.readUnsignedShort()));
                skipElementValuePairs(data, pool);
            }
        }
        return new ClassFileMetadata(
                name,
                accessFlags,
                superName,
                interfaces,
                fields,
                methods,
                annotations
        );
    }

    // 作用：跳过成员上的属性表；逻辑：逐项读取名称索引与长度后整体越过内容。
    private void skipAttributes(DataInputStream data) throws IOException {
        int count = data.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            data.readUnsignedShort();
            int length = data.readInt();
            skipFully(data, length);
        }
    }

    // 作用：跳过注解的键值对；逻辑：逐个读取元素名索引并跳过对应元素值。
    private void skipElementValuePairs(DataInputStream data, ConstantPool pool) throws IOException {
        int pairCount = data.readUnsignedShort();
        for (int index = 0; index < pairCount; index++) {
            data.readUnsignedShort();
            skipElementValue(data, pool);
        }
    }

    // 作用：跳过单个注解元素值；逻辑：按标签类型读取常量索引、枚举、嵌套注解或数组内容。
    private void skipElementValue(DataInputStream data, ConstantPool pool) throws IOException {
        int tag = data.readUnsignedByte();
        switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> data.readUnsignedShort();
            case 'e' -> {
                data.readUnsignedShort();
                data.readUnsignedShort();
            }
            case '@' -> {
                data.readUnsignedShort();
                skipElementValuePairs(data, pool);
            }
            case '[' -> {
                int count = data.readUnsignedShort();
                for (int index = 0; index < count; index++) {
                    skipElementValue(data, pool);
                }
            }
            default -> throw new IOException("Unsupported annotation element tag: " + (char) tag);
        }
    }

    // 作用：确保跳过指定字节数；逻辑：循环调用 skipBytes，遇到零进度时退化为逐字节读取。
    private void skipFully(DataInputStream data, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int skipped = data.skipBytes(remaining);
            if (skipped <= 0) {
                if (data.read() < 0) {
                    throw new IOException("Unexpected end of class file");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static final class ConstantPool {
        private static final int UTF8 = 1;
        private static final int CLASS = 7;

        private final int[] tags;
        private final String[] utf8;
        private final int[] classNameIndexes;

        // 作用：保存常量池解析结果；逻辑：记录标签、UTF-8 值与类名索引数组。
        private ConstantPool(int[] tags, String[] utf8, int[] classNameIndexes) {
            this.tags = tags;
            this.utf8 = utf8;
            this.classNameIndexes = classNameIndexes;
        }

        // 作用：读取 class 常量池；逻辑：按标签消费对应结构，长整型占两个槽位时跳过下一个索引。
        static ConstantPool read(DataInputStream data) throws IOException {
            int count = data.readUnsignedShort();
            int[] tags = new int[count];
            String[] utf8 = new String[count];
            int[] classNameIndexes = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = data.readUnsignedByte();
                tags[index] = tag;
                switch (tag) {
                    case 1 -> utf8[index] = data.readUTF();
                    case 3, 4 -> data.readInt();
                    case 5, 6 -> {
                        data.readLong();
                        index++;
                    }
                    case 7 -> classNameIndexes[index] = data.readUnsignedShort();
                    case 8, 16, 19, 20 -> data.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        data.readUnsignedShort();
                        data.readUnsignedShort();
                    }
                    case 15 -> {
                        data.readUnsignedByte();
                        data.readUnsignedShort();
                    }
                    default -> throw new IOException("Unsupported constant pool tag: " + tag);
                }
            }
            return new ConstantPool(tags, utf8, classNameIndexes);
        }

        // 作用：读取 UTF-8 常量；逻辑：校验索引与标签后返回缓存文本。
        String utf8(int index) throws IOException {
            if (index <= 0 || index >= tags.length || tags[index] != UTF8) {
                throw new IOException("Invalid UTF-8 constant pool index: " + index);
            }
            return utf8[index];
        }

        // 作用：读取类名常量；逻辑：校验类常量索引，再取 UTF-8 值并把内部斜杠替换为点号。
        String className(int index) throws IOException {
            if (index <= 0 || index >= tags.length || tags[index] != CLASS) {
                throw new IOException("Invalid class constant pool index: " + index);
            }
            return utf8(classNameIndexes[index]).replace('/', '.');
        }
    }
}
