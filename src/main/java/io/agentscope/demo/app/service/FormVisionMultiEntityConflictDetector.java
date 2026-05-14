package io.agentscope.demo.app.service;

import io.agentscope.demo.app.web.dto.AmbiguousFieldDto;
import io.agentscope.demo.app.web.dto.AmbiguousOptionDto;
import io.agentscope.demo.app.web.dto.FormVisionExtraction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 在 {@link FormVisionPatchNormalizer} 之后执行：若多证出现<strong>互斥</strong>的企业名称或统一社会信用代码候选，则从
 * {@code form_patch} 中剔除冲突键并注入 {@code ambiguities}，与技能 {@code form_vision_fill}「多主体」规则对齐。
 */
public final class FormVisionMultiEntityConflictDetector {

    private static final Pattern ORG_MARKERS =
            Pattern.compile("(公司|企业|厂|中心|集团|合作社|经营部|商行|店|个体工商户)");
    private static final Pattern YE_HU = Pattern.compile("业户名称\\s*[:：]\\s*([^；;\\n]+)");
    private static final Pattern QIYE_NAME = Pattern.compile("企业名称\\s*[:：]\\s*([^；;\\n]+)");
    private FormVisionMultiEntityConflictDetector() {}

    /**
     * @param extraction 当前轮视觉结构化结果（{@code form_patch} 已归一化）
     * @param rawPatch 归一化前模型原始 {@code form_patch} 快照（用于读取已被白名单丢弃的「业户名称 / 企业名称」等键）
     */
    public static void apply(FormVisionExtraction extraction, Map<String, Object> rawPatch) {
        if (extraction == null) {
            return;
        }
        Map<String, Object> raw = rawPatch == null ? Map.of() : rawPatch;
        LinkedHashMap<String, Object> patch = ensureMutablePatch(extraction);

        LinkedHashSet<String> companyCandidates = new LinkedHashSet<>();
        collectCompanyNamesFromRaw(raw, companyCandidates);
        addIfNonBlank(companyCandidates, stringVal(patch.get("companyName")));
        extractEmbeddedNames(stringVal(patch.get("transportAdminLicenseName")), YE_HU, companyCandidates);
        extractEmbeddedNames(stringVal(patch.get("safetyAdminLicenseName")), QIYE_NAME, companyCandidates);

        List<String> orgLike = companyCandidates.stream().filter(FormVisionMultiEntityConflictDetector::looksLikeOrgName).distinct().toList();
        List<String> conflictCluster = buildConflictCluster(orgLike);
        if (conflictCluster.size() >= 2 && !hasAmbiguityFor(extraction, "companyName")) {
            patch.remove("companyName");
            patch.remove("companyShortName");
            appendAmbiguity(extraction, companyNameAmbiguity(conflictCluster));
            appendReplyHint(extraction);
        }

        LinkedHashSet<String> usccCandidates = new LinkedHashSet<>();
        collectUsccFromRaw(raw, usccCandidates);
        addIfNonBlank(usccCandidates, stringVal(patch.get("unifiedSocialCreditCode")));
        List<String> usccDistinct = usccCandidates.stream().map(String::trim).distinct().toList();
        List<String> usccConflict = buildUsccConflictCluster(usccDistinct);
        if (usccConflict.size() >= 2 && !hasAmbiguityFor(extraction, "unifiedSocialCreditCode")) {
            patch.remove("unifiedSocialCreditCode");
            appendAmbiguity(extraction, usccAmbiguity(usccConflict));
            appendReplyHint(extraction);
        }
    }

    private static LinkedHashMap<String, Object> ensureMutablePatch(FormVisionExtraction extraction) {
        if (!(extraction.formPatch instanceof LinkedHashMap<?, ?>)) {
            extraction.formPatch = new LinkedHashMap<>(extraction.formPatch);
        }
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, Object> patch = (LinkedHashMap<String, Object>) extraction.formPatch;
        return patch;
    }

    private static void appendReplyHint(FormVisionExtraction extraction) {
        String hint =
                "\n\n【系统提示】检测到多张证照上的企业名称或统一社会信用代码存在互斥候选，已从自动回填中移除对应字段；"
                        + "请在右侧表单上方黄色「需您确认」区域点选确认后再继续。";
        String r = extraction.reply;
        if (r == null || r.isBlank()) {
            extraction.reply = hint.trim();
        } else if (!r.contains("【系统提示】检测到多张证照")) {
            extraction.reply = r + hint;
        }
    }

    private static boolean hasAmbiguityFor(FormVisionExtraction extraction, String fieldKey) {
        if (extraction.ambiguities == null) {
            return false;
        }
        for (AmbiguousFieldDto a : extraction.ambiguities) {
            if (fieldKey.equals(a.fieldKey)) {
                return true;
            }
        }
        return false;
    }

    private static void appendAmbiguity(FormVisionExtraction extraction, AmbiguousFieldDto dto) {
        List<AmbiguousFieldDto> list = mutableAmbiguities(extraction);
        list.add(dto);
        extraction.ambiguities = list;
    }

    private static List<AmbiguousFieldDto> mutableAmbiguities(FormVisionExtraction extraction) {
        if (extraction.ambiguities == null || extraction.ambiguities.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(extraction.ambiguities);
    }

    private static AmbiguousFieldDto companyNameAmbiguity(List<String> names) {
        AmbiguousFieldDto a = new AmbiguousFieldDto();
        a.fieldKey = "companyName";
        a.questionForUser =
                "多张证照上出现不同的「企业 / 业户名称」，无法自动确定表单「公司名称」应以哪一证为准；请任选其一。";
        int i = 0;
        for (String n : names) {
            String id = "ent_" + (i++);
            a.options.add(new AmbiguousOptionDto(id, "证照所示企业名称：" + n, n));
        }
        return a;
    }

    private static AmbiguousFieldDto usccAmbiguity(List<String> codes) {
        AmbiguousFieldDto a = new AmbiguousFieldDto();
        a.fieldKey = "unifiedSocialCreditCode";
        a.questionForUser =
                "多张证照上出现不同的统一社会信用代码（或含掩码与完整号码混排），无法自动确定应以哪一证为准；请任选其一。";
        int i = 0;
        for (String c : codes) {
            String id = "uscc_" + (i++);
            a.options.add(new AmbiguousOptionDto(id, "证照所示统一社会信用代码：" + c, c));
        }
        return a;
    }

    private static List<String> buildConflictCluster(List<String> orgLike) {
        LinkedHashSet<String> inConflict = new LinkedHashSet<>();
        for (int i = 0; i < orgLike.size(); i++) {
            for (int j = i + 1; j < orgLike.size(); j++) {
                if (incompatibleOrgNames(orgLike.get(i), orgLike.get(j))) {
                    inConflict.add(orgLike.get(i));
                    inConflict.add(orgLike.get(j));
                }
            }
        }
        return new ArrayList<>(inConflict);
    }

    private static List<String> buildUsccConflictCluster(List<String> codes) {
        LinkedHashSet<String> inConflict = new LinkedHashSet<>();
        for (int i = 0; i < codes.size(); i++) {
            for (int j = i + 1; j < codes.size(); j++) {
                if (incompatibleUscc(codes.get(i), codes.get(j))) {
                    inConflict.add(codes.get(i));
                    inConflict.add(codes.get(j));
                }
            }
        }
        return new ArrayList<>(inConflict);
    }

    private static boolean incompatibleOrgNames(String a, String b) {
        String na = normalizeOrg(a);
        String nb = normalizeOrg(b);
        if (na.length() < 4 || nb.length() < 4) {
            return false;
        }
        if (na.equals(nb)) {
            return false;
        }
        if (na.contains(nb) || nb.contains(na)) {
            return false;
        }
        return true;
    }

    private static String normalizeOrg(String s) {
        return s.replace('\u3000', ' ')
                .trim()
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeOrgName(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.length() < 4) {
            return false;
        }
        return ORG_MARKERS.matcher(t).find() || t.length() >= 6;
    }

    private static boolean incompatibleUscc(String a, String b) {
        String na = normalizeUscc(a);
        String nb = normalizeUscc(b);
        if (na.length() < 10 || nb.length() < 10) {
            return false;
        }
        return !na.equals(nb);
    }

    private static String normalizeUscc(String s) {
        return s.replaceAll("\\s", "").replace("*", "").toUpperCase(Locale.ROOT);
    }

    private static void collectCompanyNamesFromRaw(Map<String, Object> raw, LinkedHashSet<String> out) {
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey() == null || !(e.getValue() instanceof String)) {
                continue;
            }
            String k = e.getKey().toLowerCase(Locale.ROOT);
            String v = ((String) e.getValue()).trim();
            if (v.isEmpty()) {
                continue;
            }
            if ("companyname".equals(k) || "name".equals(k)) {
                out.add(v);
                continue;
            }
            if (k.contains("businesslicense") && (k.contains("name") || k.contains("title"))) {
                out.add(v);
                continue;
            }
            if ((k.contains("safety") || k.contains("hazard")) && k.contains("company")) {
                out.add(v);
                continue;
            }
            if (k.contains("transport") && k.contains("company")) {
                out.add(v);
                continue;
            }
            if (k.contains("safety") && k.contains("holder")) {
                out.add(v);
                continue;
            }
            if (k.contains("transport") && k.contains("holder")) {
                out.add(v);
                continue;
            }
        }
    }

    private static void collectUsccFromRaw(Map<String, Object> raw, LinkedHashSet<String> out) {
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey() == null || !(e.getValue() instanceof String)) {
                continue;
            }
            String k = e.getKey().toLowerCase(Locale.ROOT);
            String v = ((String) e.getValue()).trim();
            if (v.length() < 10) {
                continue;
            }
            if (!(k.contains("unified") || k.contains("credit") || k.contains("socialcredit"))) {
                continue;
            }
            String digits = v.replaceAll("[^0-9A-Za-z*]", "");
            if (digits.length() >= 15) {
                out.add(v);
            }
        }
    }

    private static void extractEmbeddedNames(String blob, Pattern p, LinkedHashSet<String> out) {
        if (blob == null || blob.isBlank()) {
            return;
        }
        var m = p.matcher(blob);
        while (m.find()) {
            String g = m.group(1).trim();
            if (!g.isEmpty()) {
                out.add(g);
            }
        }
    }

    private static void addIfNonBlank(LinkedHashSet<String> out, String s) {
        if (s != null && !s.isBlank()) {
            out.add(s.trim());
        }
    }

    private static String stringVal(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
