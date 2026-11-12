package io.fieldtracegraph.javaanalysis.io;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ErrorHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

// 作用：安全读取 XML，禁用外部实体与 DTD。
// 使用场景：解析 MyBatis Mapper XML，防止 XXE 攻击。

public final class SafeXmlReader {
    // 作用：阻止实例化工具类；逻辑：构造器保持私有。
    private SafeXmlReader() {
    }

    // 作用：安全解析 XML 文件；逻辑：关闭 DTD、外部实体与实体展开，并在解析错误时转为参数异常。
    public static Document parse(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ThrowingErrorHandler());
            return builder.parse(input);
        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new IllegalArgumentException("Unable to parse XML file: " + file, e);
        }
    }

    /**
     * Keep fatal parser diagnostics as exceptions instead of letting the
     * default handler print "[Fatal Error]" to stderr during normal use.
     */
    private static final class ThrowingErrorHandler implements ErrorHandler {
        @Override
        // 作用：处理解析警告；逻辑：忽略警告，不中断解析。
        public void warning(SAXParseException exception) {
            // Warnings do not prevent parsing.
        }

        @Override
        // 作用：处理可恢复解析错误；逻辑：直接抛出异常，避免继续生成不完整文档。
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        // 作用：处理致命解析错误；逻辑：直接抛出异常，交由调用方统一包装。
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
