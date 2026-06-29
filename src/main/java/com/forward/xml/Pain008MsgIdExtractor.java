package com.forward.xml;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;

/**
 * Extracts the {@code <GrpHdr><MsgId>} value from a pain.008.001.08 XML document.
 *
 * Uses a namespace-aware DOM parser so the tag is found regardless of
 * namespace prefix variations in the source file.
 */
@Component
public class Pain008MsgIdExtractor {

    /**
     * @param xmlBytes raw bytes of a pain.008.001.08 XML file
     * @return the text content of the first {@code <MsgId>} element found
     *         inside {@code <GrpHdr>}, or an empty string if not found
     * @throws Pain008ParseException if the XML cannot be parsed at all
     */
    public String extractMsgId(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            throw new Pain008ParseException("XML bytes are empty — cannot extract MsgId");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Disable external entity resolution (XXE protection)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new ByteArrayInputStream(xmlBytes)));

            // Works with or without namespace prefix because we match local name only
            NodeList msgIdNodes = doc.getElementsByTagNameNS("*", "MsgId");
            if (msgIdNodes.getLength() == 0) {
                System.out.println("  [Pain008MsgIdExtractor] ✗ <MsgId> element not found in XML");
                return "";
            }

            String msgId = msgIdNodes.item(0).getTextContent().trim();
            System.out.println("  [Pain008MsgIdExtractor] ✓ extracted MsgId: " + msgId);
            return msgId;

        } catch (Exception e) {
            throw new Pain008ParseException("Failed to parse XML for MsgId: " + e.getMessage(), e);
        }
    }

    public static class Pain008ParseException extends RuntimeException {
        public Pain008ParseException(String message) { super(message); }
        public Pain008ParseException(String message, Throwable cause) { super(message, cause); }
    }
}
