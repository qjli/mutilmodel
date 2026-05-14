package io.agentscope.demo.app.upload;

import io.agentscope.demo.app.web.dto.UploadGuideDto;
import io.agentscope.demo.app.web.dto.UploadGuideMaterialItem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 根据会话累计覆盖集合构造「仍缺证照」材料卡（与前端样图 id 一致）。 */
public final class UploadGuideFromCoverage {

    private UploadGuideFromCoverage() {}

    /**
     * @param coverageIds 已推断出现过的 {@link MaterialSampleIds} 子集
     * @return 若四类均已覆盖则 {@code null}；否则返回仅含仍缺项的 {@link UploadGuideDto}
     */
    public static UploadGuideDto buildRemaining(Set<String> coverageIds) {
        LinkedHashSet<String> cov = new LinkedHashSet<>();
        if (coverageIds != null) {
            for (String id : coverageIds) {
                if (MaterialSampleIds.CANONICAL_ORDER.contains(id)) {
                    cov.add(id);
                }
            }
        }
        List<String> missing = MaterialSampleIds.CANONICAL_ORDER.stream().filter(id -> !cov.contains(id)).toList();
        if (missing.isEmpty()) {
            return null;
        }
        UploadGuideDto dto = new UploadGuideDto();
        dto.cardTitle = "仍建议补充以下证照（示意样图）";
        dto.satisfiedLabels = new ArrayList<>();
        for (String id : MaterialSampleIds.CANONICAL_ORDER) {
            if (cov.contains(id)) {
                dto.satisfiedLabels.add(MaterialSampleIds.chineseTitle(id));
            }
        }
        dto.missingItems = new ArrayList<>();
        for (String id : missing) {
            UploadGuideMaterialItem it = new UploadGuideMaterialItem();
            it.sampleImageId = id;
            it.title = MaterialSampleIds.chineseTitle(id);
            it.subtitle = "请确保图片中文字清晰可见";
            dto.missingItems.add(it);
        }
        return dto;
    }
}
