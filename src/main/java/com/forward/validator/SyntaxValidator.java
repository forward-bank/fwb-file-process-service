package com.forward.validator;

import com.forward.model.SyntaxValidationResponse;

public class SyntaxValidator {

    public SyntaxValidationResponse validate(String paymentXmlPath) {
        System.out.println("  Validating : " + paymentXmlPath);

        if (paymentXmlPath == null || paymentXmlPath.isBlank()) {
            return SyntaxValidationResponse.invalid("SVE_001"); // missing path
        }

        if (!paymentXmlPath.endsWith(".xml")) {
            return SyntaxValidationResponse.invalid("SVE_002"); // not an XML file
        }

        // TODO: replace with real pain.008 XML schema validation
        // e.g. load the file from S3, parse it, validate against XSD

        return SyntaxValidationResponse.valid();
    }
}