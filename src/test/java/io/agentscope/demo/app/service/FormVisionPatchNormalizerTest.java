package io.agentscope.demo.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormVisionPatchNormalizerTest {

    @Test
    void normalizesSingleBusinessLicenseAliases() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("creditCode", "914105007492219803");
        raw.put("name", "河南安彩能源股份有限公司");
        raw.put("legalRep", "杨建新");
        raw.put("establishmentDate", "2003-04-18");
        raw.put("address", "安阳高新区弦歌大道西段科创大厦609室");
        raw.put("type", "股份有限公司(非上市、国有控股)");
        raw.put("registeredCapital", "壹亿贰仟零叁拾伍万伍仟柒佰叁拾贰圆整");
        raw.put("businessScope", "天然气相关业务");
        raw.put("registrar", "安阳市市场监督管理局");
        var out = FormVisionPatchNormalizer.normalize(raw);
        assertThat(out)
                .containsEntry("unifiedSocialCreditCode", "914105007492219803")
                .containsEntry("companyName", "河南安彩能源股份有限公司")
                .containsEntry("legalRepresentative", "杨建新")
                .containsEntry("registrationDate", "2003-04-18")
                .containsEntry("registeredAddressDetail", "安阳高新区弦歌大道西段科创大厦609室")
                .containsEntry("enterpriseType", "股份有限公司(非上市、国有控股)")
                .doesNotContainKey("registrar");
    }

    @Test
    void normalizesChineseRegistrationDateToIso() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("establishmentDate", "2003年04月18日");
        var out = FormVisionPatchNormalizer.normalize(raw);
        assertThat(out).containsEntry("registrationDate", "2003-04-18");
    }

    @Test
    void normalizesBatchFourPermitPrefixes() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("businessLicenseUnifiedSocialCreditCode", "914105007492219803");
        raw.put("businessLicenseName", "河南安彩能源股份有限公司");
        raw.put("idCardIdNumber", "410426198109293571");
        raw.put("transportPermitNumber", "冀交运管许可石字130101306556号");
        raw.put("transportPermitIssuingAuthority", "石家庄市行政审批局");
        raw.put("transportPermitValidityPeriodStart", "2024-10-17");
        raw.put("transportPermitValidityPeriodEnd", "2028-10-16");
        raw.put("safetyPermitNumber", "1401000000000001");
        raw.put("safetyPermitIssuingAuthority", "山东省应急管理厅");
        raw.put("safetyPermitLegalRepresentative", "张某某");
        raw.put("safetyPermitValidityPeriodStart", "2025-03-20");
        raw.put("safetyPermitValidityPeriodEnd", "2028-03-19");
        var out = FormVisionPatchNormalizer.normalize(raw);
        assertThat(out.get("unifiedSocialCreditCode")).isEqualTo("914105007492219803");
        assertThat(out.get("companyName")).isEqualTo("河南安彩能源股份有限公司");
        assertThat(out.get("qualificationIdDocNumber")).isEqualTo("410426198109293571");
        assertThat(out.get("qualificationIdDocType")).isEqualTo("身份证");
        assertThat(out.get("transportLicenseNo")).isEqualTo("冀交运管许可石字130101306556号");
        assertThat(out.get("transportIssuingAuthority")).isEqualTo("石家庄市行政审批局");
        assertThat(out.get("transportLicenseValidityRange")).isEqualTo(List.of("2024-10-17", "2028-10-16"));
        assertThat(out.get("transportLicenseValidityMode")).isEqualTo("fixed");
        assertThat(out.get("safetyLicenseNo")).isEqualTo("1401000000000001");
        assertThat(out.get("safetyIssuingAuthority")).isEqualTo("山东省应急管理厅");
        assertThat(out.get("safetyLegalRepresentative")).isEqualTo("张某某");
        assertThat(out.get("safetyLicenseValidityRange")).isEqualTo(List.of("2025-03-20", "2028-03-19"));
    }
}
