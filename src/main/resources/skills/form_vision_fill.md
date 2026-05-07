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
- 安全生产：`safetyAdminLicenseName`, `safetyLicenseNo`, `safetyLicenseValidityMode`（`fixed` 或 `long`）
  - 当 `safetyLicenseValidityMode` 为 `fixed` 时，用 `safetyLicenseValidityRange`：**两元素 ISO 日期字符串数组** `[start,end]`
- 危化运输：`transportAdminLicenseName`, `transportLicenseNo`, `transportLicenseValidityMode`
  - 当为 `fixed` 时同样输出 `transportLicenseValidityRange` 为 `[start,end]` 的 ISO 字符串数组

只输出**有把握**从图片读到的字段；不确定的不要猜进 `form_patch`。

## 歧义（ambiguities）

若某字段存在 **2 个及以上** 合理候选（字号模糊、遮挡、多证并列等），**不要**在 `form_patch` 中写入该字段，改为在 `ambiguities` 中给出：

- `field_key`：同上 camelCase 字段名
- `question_for_user`：一句中文，说明为何需要用户确认
- `options`：每项含 `option_id`（简短 id）、`label`（展示文案）、`suggested_value`（用户确认后应写入表单的值，通常与选项语义一致）

## 回复（reply）

`reply` 用简短中文概括识别摘要（不含 Markdown 代码块）。
