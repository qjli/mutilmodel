package io.agentscope.demo.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.demo.app.web.dto.FormVisionExtraction;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class FormVisionMultiEntityConflictDetectorTest {

    @Test
    void removesCompanyNameAndAddsAmbiguityWhenHenanVsShandong() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("businessLicenseName", "河南安彩能源股份有限公司");
        raw.put("safetyCompanyName", "山东某某化工有限公司");
        raw.put("creditCode", "914105007492219803");

        FormVisionExtraction ext = new FormVisionExtraction();
        ext.formPatch = FormVisionPatchNormalizer.normalize(raw);
        FormVisionMultiEntityConflictDetector.apply(ext, raw);

        assertThat(ext.formPatch).doesNotContainKey("companyName");
        assertThat(ext.ambiguities).anyMatch(a -> "companyName".equals(a.fieldKey) && a.options.size() >= 2);
    }

    @Test
    void removesUnifiedSocialCreditWhenTwoDistinctCodes() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("businessLicenseUnifiedSocialCreditCode", "914105007492219803");
        raw.put("safetyUnifiedSocialCreditCode", "9114000000000000X1");

        FormVisionExtraction ext = new FormVisionExtraction();
        ext.formPatch = FormVisionPatchNormalizer.normalize(raw);
        FormVisionMultiEntityConflictDetector.apply(ext, raw);

        assertThat(ext.formPatch).doesNotContainKey("unifiedSocialCreditCode");
        assertThat(ext.ambiguities).anyMatch(a -> "unifiedSocialCreditCode".equals(a.fieldKey));
    }

    @Test
    void doesNotTriggerWhenSingleCompany() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "河南安彩能源股份有限公司");
        raw.put("transportCompanyName", "河南安彩能源股份有限公司");

        FormVisionExtraction ext = new FormVisionExtraction();
        ext.formPatch = FormVisionPatchNormalizer.normalize(raw);
        FormVisionMultiEntityConflictDetector.apply(ext, raw);

        assertThat(ext.formPatch.get("companyName")).isEqualTo("河南安彩能源股份有限公司");
        assertThat(ext.ambiguities.stream().noneMatch(a -> "companyName".equals(a.fieldKey))).isTrue();
    }
}
