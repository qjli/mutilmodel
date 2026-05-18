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
        assertThat(ext.formPatch).doesNotContainKey("unifiedSocialCreditCode");
        assertThat(ext.ambiguities).anyMatch(a -> "companyName".equals(a.fieldKey) && a.options.size() >= 2);
        assertThat(ext.ambiguities).anyMatch(a -> "unifiedSocialCreditCode".equals(a.fieldKey) && a.options.size() >= 2);
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
        assertThat(ext.ambiguities).anyMatch(a -> "unifiedSocialCreditCode".equals(a.fieldKey) && a.options.size() >= 2);
        assertThat(ext.formPatch).doesNotContainKey("companyName");
    }

    @Test
    void detectsTwoUsccsEmbeddedInSingleLongOcrField() {
        String ocr =
                "执照一 统一社会信用代码 914105007492219803 执照二 统一社会信用代码 91610132MADAMFTL7T 其它说明";
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("modelOcrNotes", ocr);
        raw.put("companyName", "河南安彩能源股份有限公司");

        FormVisionExtraction ext = new FormVisionExtraction();
        ext.formPatch = FormVisionPatchNormalizer.normalize(raw);
        FormVisionMultiEntityConflictDetector.apply(ext, raw);

        assertThat(ext.formPatch).doesNotContainKey("unifiedSocialCreditCode");
        assertThat(ext.ambiguities).anyMatch(a -> "unifiedSocialCreditCode".equals(a.fieldKey) && a.options.size() >= 2);
    }

    @Test
    void detectsTwoCompanyNamesFromLabeledTextWhenPatchHasOnlyOne() {
        String blob =
                "第一张 名称：河南安彩能源股份有限公司 成立日期 2003年；第二张 名称：陕西中辰海锋新能源有限公司 成立日期 2024年";
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("licenseSummary", blob);
        raw.put("unifiedSocialCreditCode", "914105007492219803");

        FormVisionExtraction ext = new FormVisionExtraction();
        ext.formPatch = FormVisionPatchNormalizer.normalize(raw);
        FormVisionMultiEntityConflictDetector.apply(ext, raw);

        assertThat(ext.formPatch).doesNotContainKey("companyName");
        assertThat(ext.ambiguities).anyMatch(a -> "companyName".equals(a.fieldKey) && a.options.size() >= 2);
        assertThat(ext.ambiguities).anyMatch(a -> "unifiedSocialCreditCode".equals(a.fieldKey) && a.options.size() >= 2);
    }

    @Test
    void stripsTransportFamilyWhenTwoDistinctPermitNumbers() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("transportPermitNumber", "豫交运管许可危字111111号");
        raw.put("transportNumber", "豫交运管许可危字222222号");
        raw.put("transportIssueAuthority", "郑州市交通运输局");

        FormVisionExtraction ext = new FormVisionExtraction();
        ext.formPatch = FormVisionPatchNormalizer.normalize(raw);
        FormVisionMultiEntityConflictDetector.apply(ext, raw);

        assertThat(ext.formPatch).doesNotContainKey("transportLicenseNo");
        assertThat(ext.ambiguities).anyMatch(a -> "transportLicenseNo".equals(a.fieldKey) && a.options.size() >= 2);
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
