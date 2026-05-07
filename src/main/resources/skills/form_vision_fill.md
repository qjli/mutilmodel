# form_vision_fill

面向「企业基本信息 / 企业资质 / 专业资质」结构化表单的视觉识别与抽取。

## 何时加载

用户上传营业执照、许可证、资质扫描件等多张图片，需要从影像中回填右侧表单字段时，必须加载本技能并严格按其字段约定输出。

## 字段键（form_patch）

`form_patch` 的键名必须使用 **camelCase**，与前端表单 `name` 一致，例如：

- `companyName`, `companyShortName`, `formerName`
- `enterpriseNature`, `enterpriseType`, `businessScope`
- `companyPhone`, `companyEmail`
- `registrationDate`（ISO-8601 日期字符串，如 `2018-03-15`）
- `registeredRegion`, `registeredAddressDetail`, `actualLocation`, `registeredZip`
- `registeredCapital`（字符串，单位万元，与表单一致）
- `companyFax`, `learnChannel`
- `unifiedSocialCreditCode`, `qualificationIdDocType`, `qualificationIdDocNumber`
- **安全生产 / 危险品经营许可证等**（与表单「危险品经营许可证」区块一致）：
  - `safetyAdminLicenseName`：证照上的行政许可名称 / 证书名称（如「危险化学品经营许可证」）
  - `safetyLicenseNo`：证书编号 / 许可证编号
  - `safetyLicenseValidityMode`：`fixed`（有明确起止日期）或 `long`（载明「长期」「长期有效」等）
  - 当 `fixed` 时：`safetyLicenseValidityRange` 为 **两元素 ISO 日期字符串数组** `[start,end]`（与证面「有效期自/至」「有效期限」一致）
  - **`safetyIssuingAuthority`（发证机构，必填尽量抽取）**：发证机关全称。证面常见标签见下文「发证机构 / 法人代表」小节。
  - **`safetyLegalRepresentative`（法定代表人或负责人，必填尽量抽取）**：证面「法定代表人」「主要负责人」「企业负责人」等；若仅出现「负责人」且无更具体称谓，仍填入本字段。
- **危化品运输许可证**（若影像为该证）：
  - `transportAdminLicenseName`, `transportLicenseNo`, `transportLicenseValidityMode`
  - 当 `fixed` 时：`transportLicenseValidityRange` 为 `[start,end]` 的 ISO 字符串数组
  - `transportIssuingAuthority`、`transportLegalRepresentative`：版式与安全生产类证照类似，按证面同义词抽取。

只输出**有把握**从图片读到的字段；不确定的不要猜进 `form_patch`。

## 危险品经营许可证 / 安全生产类证照：发证机构与法人代表

此类证照（含「危险化学品经营许可证」「安全生产许可证」等变体）上，**发证机构**与**法定代表人/负责人**经常出现在下列位置，须逐项核对，避免漏抽：

1. **版式位置**
   - 正文区中部或偏下：成对出现「发证机关：……」「法定代表人：……」或表格单元格。
   - 右下角或骑缝章附近：红色公章上的单位名称往往就是 **发证机关**；公章旁或编号下方可能有机关打印名称，与公章一致时取**打印全称**（更清晰则优先用打印字）。
   - 页眉、页脚、侧边栏：部分省份模板把「发证机关」放在证书底部横条。
2. **发证机构 → `safetyIssuingAuthority` 的常见字样（等价识别）**
   - 「发证机关」「发证单位」「颁证机关」「许可机关」「审批机关」「发证部门」
   - 「（盖章）」上一行或左侧的行政机关全称（如「××市应急管理局」「××省应急管理厅」）
   - 若证面仅有公章无打印机关名：可据公章环内/章边可辨文字推断；**仍无把握时**不要编造，可放入 `ambiguities` 让用户确认，或留空不写 `form_patch` 该项。
3. **法定代表人 / 负责人 → `safetyLegalRepresentative` 的常见字样**
   - 「法定代表人」「法定负责人」「企业法定代表人」
   - 「主要负责人」「负责人」「企业主要负责人」（危化经营许可证常用「主要负责人」表述，**一律映射到 `safetyLegalRepresentative`**）
   - 勿与「经办人」「领证」混淆；经办人不是法人代表字段。
4. **与其它字段区分**
   - 「企业名称」「经营者名称」「单位名称」对应的是经营主体，**不是** `safetyLegalRepresentative`（除非证面明确写该人即为法定代表人且与姓名行绑定，此时姓名写入 `safetyLegalRepresentative`，企业名称可对应 `companyName` 等若你也识别营业执照）。
   - 若证面同时出现「法定代表人」与「主要负责人」且为不同人：以证面**主栏或加粗/表格主行**为准；仍无法判断则写入 `ambiguities`（`field_key` 用 `safetyLegalRepresentative`），选项为两个不同姓名。

**优先级**：只要危险品经营许可证（或同类安全生产许可）影像清晰可读，应**尽量**输出 `safetyIssuingAuthority` 与 `safetyLegalRepresentative`；二者在证面上通常有明确标签，漏识别视为未完成本技能的核心任务。

## 歧义（ambiguities）

若某字段存在 **2 个及以上** 合理候选（字号模糊、遮挡、多证并列等），**不要**在 `form_patch` 中写入该字段，改为在 `ambiguities` 中给出：

- `field_key`：同上 camelCase 字段名
- `question_for_user`：一句中文，说明为何需要用户确认
- `options`：每项含 `option_id`（简短 id）、`label`（展示文案）、`suggested_value`（用户确认后应写入表单的值，通常与选项语义一致）

## 回复（reply）

`reply` 用简短中文概括识别摘要（不含 Markdown 代码块）。
