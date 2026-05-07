import "./App.css";
import {
  CloudUploadOutlined,
  FileProtectOutlined,
  FormOutlined,
  MessageOutlined,
  QuestionCircleOutlined,
  SafetyCertificateOutlined,
  SendOutlined,
} from "@ant-design/icons";
import {
  Alert,
  App,
  Badge,
  Button,
  Col,
  Collapse,
  DatePicker,
  Divider,
  Form,
  Input,
  Progress,
  Radio,
  Row,
  Select,
  Space,
  Tooltip,
  Typography,
} from "antd";
import dayjs from "dayjs";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { createPortal } from "react-dom";
import {
  postJson,
  postVisionFormStream,
  type VisionAmbiguousField,
} from "./api";
import { AppHeader } from "./components/AppHeader";

type ChatResponse = {
  reply: string;
  formPatch?: Record<string, unknown>;
  serverTime: string;
};

type FormValues = {
  companyName: string;
  companyShortName: string;
  formerName?: string;
  enterpriseNature?: string;
  enterpriseType?: string;
  businessScope?: string;
  companyPhone?: string;
  companyEmail?: string;
  registrationDate?: dayjs.Dayjs;
  registeredRegion?: string;
  registeredAddressDetail?: string;
  actualLocation?: string;
  registeredZip?: string;
  registeredCapital?: string;
  companyFax?: string;
  learnChannel?: string;
  /** 企业资质信息 */
  unifiedSocialCreditCode?: string;
  qualificationIdDocType?: string;
  qualificationIdDocNumber?: string;
  /** 专业资质 — 安全生产相关 */
  safetyAdminLicenseName?: string;
  safetyLicenseNo?: string;
  safetyLicenseValidityMode?: "fixed" | "long";
  /** 固定日期模式下：许可有效期起止 */
  safetyLicenseValidityRange?: [dayjs.Dayjs, dayjs.Dayjs];
  safetyIssuingAuthority?: string;
  safetyLegalRepresentative?: string;
  /** 专业资质 — 危化品运输 */
  transportAdminLicenseName?: string;
  transportLicenseNo?: string;
  transportLicenseValidityMode?: "fixed" | "long";
  transportLicenseValidityRange?: [dayjs.Dayjs, dayjs.Dayjs];
  transportIssuingAuthority?: string;
  transportLegalRepresentative?: string;
};

const FORM_STORAGE_PREFIX = "multimodal-demo:form:";

const FORM_RANGE_KEYS = [
  "safetyLicenseValidityRange",
  "transportLicenseValidityRange",
] as const;

const LEGACY_SINGLE_DATE_TO_RANGE: Record<string, string> = {
  safetyLicenseValidityDate: "safetyLicenseValidityRange",
  transportLicenseValidityDate: "transportLicenseValidityRange",
};

type VisionJobState = {
  status: "running" | "done" | "error";
  phase: string;
  done: number;
  total: number;
  label?: string;
  fileName?: string;
  thinkingLog: string;
  assistantLog: string;
  errorMessage?: string;
};

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  pending?: boolean;
  vision?: VisionJobState;
  createdAt: number;
};

function normalizeVisionFormPatch(patch: Record<string, unknown>): Partial<FormValues> {
  const out = { ...patch } as Record<string, unknown>;
  const reg = out.registrationDate;
  if (typeof reg === "string" && reg) {
    out.registrationDate = dayjs(reg);
  }
  for (const key of FORM_RANGE_KEYS) {
    const val = out[key];
    if (
      Array.isArray(val) &&
      val.length === 2 &&
      typeof val[0] === "string" &&
      typeof val[1] === "string"
    ) {
      const d0 = dayjs(val[0]);
      const d1 = dayjs(val[1]);
      if (d0.isValid() && d1.isValid()) {
        out[key] = [d0, d1];
      }
    }
  }
  return out as Partial<FormValues>;
}

function visionProgressPercent(v: VisionJobState): number {
  if (v.status === "done" || v.status === "error") {
    return 100;
  }
  const t = Math.max(1, v.total);
  if (v.phase === "infer") {
    const extra = Math.floor((v.thinkingLog.length + v.assistantLog.length) / 120);
    return Math.min(95, 55 + Math.min(40, extra));
  }
  return Math.round((v.done / t) * 55);
}

type RailFocusKey = "chat" | "form" | "enterpriseQual" | "professionalQual";

function RailSegmentDots({ count }: { count: number }) {
  return (
    <div className="app-rail__dots" aria-hidden>
      {Array.from({ length: count }, (_, i) => (
        <span key={i} className="app-rail__dot" />
      ))}
    </div>
  );
}

function formatClock(ts: number) {
  return new Date(ts).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

export default function MultimodalConsole() {
  const { message, notification } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const safetyLicenseValidityMode = Form.useWatch("safetyLicenseValidityMode", form);
  const transportLicenseValidityMode = Form.useWatch("transportLicenseValidityMode", form);
  const sessionId = useMemo(() => crypto.randomUUID(), []);
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: "welcome",
      role: "assistant",
      content:
        "欢迎使用多模态智能填报助手。左侧为对话流，右侧为结构化表单；文本对话可抽取字段。底部「上传文件」支持一次多选图片：将走 AgentScope 视觉 + Skill + 会话持久化 + 结构化输出，并在对话中显示读取进度、思考流与汇总；若模型列出歧义项，请在表单上方按提示点选确认。",
      createdAt: Date.now(),
    },
  ]);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [visionBusy, setVisionBusy] = useState(false);
  const [ambiguities, setAmbiguities] = useState<VisionAmbiguousField[]>([]);
  const [railFocus, setRailFocus] = useState<RailFocusKey>("chat");
  const chatSectionRef = useRef<HTMLElement | null>(null);
  const formSectionRef = useRef<HTMLElement | null>(null);
  const enterpriseQualSectionRef = useRef<HTMLDivElement | null>(null);
  const professionalQualSectionRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const pageRootRef = useRef<HTMLDivElement | null>(null);
  const headerShellRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const root = pageRootRef.current;
    const slot = headerShellRef.current;
    if (!root || !slot) {
      return;
    }
    const apply = () => {
      const h = Math.ceil(slot.getBoundingClientRect().height);
      root.style.setProperty("--app-header-offset", `${h}px`);
    };
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(slot);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    const raw = localStorage.getItem(FORM_STORAGE_PREFIX + sessionId);
    if (!raw) {
      return;
    }
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>;
      for (const [legacyKey, rangeKey] of Object.entries(LEGACY_SINGLE_DATE_TO_RANGE)) {
        const legacy = parsed[legacyKey];
        if (legacy != null && parsed[rangeKey] == null) {
          const d = typeof legacy === "string" ? dayjs(legacy) : null;
          if (d?.isValid()) {
            parsed[rangeKey] = [d.toISOString(), d.toISOString()];
          }
          delete parsed[legacyKey];
        }
      }
      const reg = parsed.registrationDate;
      if (typeof reg === "string" && reg) {
        parsed.registrationDate = dayjs(reg);
      }
      for (const key of FORM_RANGE_KEYS) {
        const val = parsed[key];
        if (
          Array.isArray(val) &&
          val.length === 2 &&
          typeof val[0] === "string" &&
          typeof val[1] === "string"
        ) {
          parsed[key] = [dayjs(val[0]), dayjs(val[1])];
        }
      }
      form.setFieldsValue(parsed as FormValues);
    } catch {
      /* ignore */
    }
  }, [form, sessionId]);

  const persistForm = useCallback(() => {
    const v = form.getFieldsValue(true) as Record<string, unknown>;
    const serializable: Record<string, unknown> = { ...v };
    const rd = serializable.registrationDate;
    if (rd && dayjs.isDayjs(rd)) {
      serializable.registrationDate = (rd as dayjs.Dayjs).toISOString();
    }
    for (const key of FORM_RANGE_KEYS) {
      const val = serializable[key];
      if (
        Array.isArray(val) &&
        val.length === 2 &&
        dayjs.isDayjs(val[0]) &&
        dayjs.isDayjs(val[1])
      ) {
        serializable[key] = [
          (val[0] as dayjs.Dayjs).toISOString(),
          (val[1] as dayjs.Dayjs).toISOString(),
        ];
      }
    }
    delete serializable.safetyLicenseValidityDate;
    delete serializable.transportLicenseValidityDate;
    localStorage.setItem(
      FORM_STORAGE_PREFIX + sessionId,
      JSON.stringify(serializable),
    );
  }, [form, sessionId]);

  const onPickFiles = () => {
    if (visionBusy) {
      message.warning("图片识别进行中，请稍候再上传");
      return;
    }
    fileInputRef.current?.click();
  };

  const onFileInputChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (files.length === 0) {
      return;
    }
    const now = Date.now();
    const listText =
      files.length === 1
        ? `上传图片：${files[0].name}`
        : `上传 ${files.length} 张图片：${files.map((f) => f.name).join("、")}`;
    const userMsgId = crypto.randomUUID();
    const assistantId = crypto.randomUUID();

    setVisionBusy(true);
    setMessages((m) => [
      ...m,
      { id: userMsgId, role: "user", content: listText, createdAt: now },
      {
        id: assistantId,
        role: "assistant",
        content: "",
        createdAt: now,
        vision: {
          status: "running",
          phase: "load_image",
          done: 0,
          total: files.length,
          thinkingLog: "",
          assistantLog: "",
        },
      },
    ]);

    const patchVision = (fn: (v: VisionJobState) => VisionJobState) => {
      setMessages((m) =>
        m.map((row) =>
          row.id === assistantId && row.vision ? { ...row, vision: fn(row.vision) } : row,
        ),
      );
    };

    try {
      await postVisionFormStream(sessionId, files, (ev) => {
        if (ev.type === "progress") {
          patchVision((v) => ({
            ...v,
            phase: ev.phase,
            done: ev.done,
            total: ev.total,
            label: ev.label,
            fileName: ev.fileName,
          }));
        } else if (ev.type === "thinking") {
          patchVision((v) => ({ ...v, thinkingLog: v.thinkingLog + ev.delta }));
        } else if (ev.type === "assistant_text") {
          patchVision((v) => ({ ...v, assistantLog: v.assistantLog + ev.delta }));
        } else if (ev.type === "result") {
          const patch = normalizeVisionFormPatch(ev.formPatch ?? {});
          form.setFieldsValue(patch);
          persistForm();
          setAmbiguities(ev.ambiguities ?? []);
          setMessages((m) =>
            m.map((row) =>
              row.id === assistantId
                ? {
                    ...row,
                    content: ev.reply ?? "",
                    vision: row.vision
                      ? {
                          ...row.vision,
                          status: "done",
                          phase: "done",
                          done: row.vision.total,
                          label: "已完成",
                        }
                      : undefined,
                  }
                : row,
            ),
          );
        } else if (ev.type === "error") {
          setMessages((m) =>
            m.map((row) =>
              row.id === assistantId
                ? {
                    ...row,
                    content: `识别出错：${ev.message}`,
                    vision: row.vision
                      ? {
                          ...row.vision,
                          status: "error",
                          errorMessage: ev.message,
                          phase: "error",
                        }
                      : undefined,
                  }
                : row,
            ),
          );
        }
      });
    } catch (err) {
      setMessages((m) =>
        m.map((row) =>
          row.id === assistantId
            ? {
                ...row,
                content: `请求失败：${err instanceof Error ? err.message : String(err)}`,
                vision: row.vision
                  ? {
                      ...row.vision,
                      status: "error",
                      errorMessage: err instanceof Error ? err.message : String(err),
                      phase: "error",
                    }
                  : undefined,
              }
            : row,
        ),
      );
      notification.error({
        message: "图片识别失败",
        description: err instanceof Error ? err.message : String(err),
      });
    } finally {
      setVisionBusy(false);
    }
  };

  const sendChat = async () => {
    const text = draft.trim();
    if (!text || sending) {
      return;
    }
    const now = Date.now();
    const userMsg: ChatMessage = {
      id: crypto.randomUUID(),
      role: "user",
      content: text,
      createdAt: now,
    };
    const pendingId = crypto.randomUUID();
    setMessages((m) => [
      ...m,
      userMsg,
      { id: pendingId, role: "assistant", content: "", pending: true, createdAt: now },
    ]);
    setDraft("");
    setSending(true);
    try {
      const data = await postJson<ChatResponse>(
        `/api/sessions/${encodeURIComponent(sessionId)}/messages`,
        { content: text },
      );
      if (data.formPatch) {
        form.setFieldsValue(normalizeVisionFormPatch(data.formPatch));
        persistForm();
      }
      setMessages((m) =>
        m.map((row) =>
          row.id === pendingId
            ? { ...row, content: data.reply, pending: false, createdAt: Date.now() }
            : row,
        ),
      );
    } catch (e) {
      setMessages((m) => m.filter((row) => row.id !== pendingId));
      notification.error({
        message: "发送失败",
        description: e instanceof Error ? e.message : String(e),
      });
    } finally {
      setSending(false);
    }
  };

  const onSubmitForm = async () => {
    try {
      await form.validateFields();
      message.success("校验通过（演示：未调用真实提交接口）");
    } catch {
      message.warning("请修正表单标红项后再提交");
    }
  };

  const onSaveDraft = () => {
    persistForm();
    message.success("草稿已暂存到本地");
  };

  const scrollToSection = (target: RailFocusKey) => {
    setRailFocus(target);
    const el =
      target === "chat"
        ? chatSectionRef.current
        : target === "form"
          ? formSectionRef.current
          : target === "enterpriseQual"
            ? enterpriseQualSectionRef.current
            : professionalQualSectionRef.current;
    el?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  /** 将最后一条助手消息滚入对话流可视区域；输出已落定时同时切换到「智能对话」并滚主区到对话列。 */
  useLayoutEffect(() => {
    const last = messages[messages.length - 1];
    if (!last || last.role !== "assistant") {
      return;
    }

    const assistantOutputSettled =
      !last.pending &&
      (!last.vision ||
        last.vision.status === "done" ||
        last.vision.status === "error");

    if (assistantOutputSettled) {
      setRailFocus("chat");
      chatSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    const frame = requestAnimationFrame(() => {
      const el = document.getElementById(`chat-msg-${last.id}`);
      el?.scrollIntoView({
        behavior:
          last.pending || last.vision?.status === "running" ? "auto" : "smooth",
        block: "end",
        inline: "nearest",
      });
    });
    return () => cancelAnimationFrame(frame);
  }, [messages]);

  const railEl = (
    <aside className="app-rail app-rail--timeline" aria-label="功能导航">
      <nav className="app-rail__track">
        <div className="app-rail__line" aria-hidden />
        <div className="app-rail__stack">
          <div className="app-rail__stop">
            <Tooltip title="智能对话" placement="right">
              <button
                type="button"
                className={`app-rail__btn ${railFocus === "chat" ? "app-rail__btn--active" : "app-rail__btn--idle"}`}
                onClick={() => scrollToSection("chat")}
                aria-current={railFocus === "chat" ? "true" : undefined}
              >
                <MessageOutlined />
              </button>
            </Tooltip>
          </div>
          <RailSegmentDots count={3} />
          <div className="app-rail__stop">
            <Tooltip title="表单填报（企业基本信息）" placement="right">
              <button
                type="button"
                className={`app-rail__btn ${railFocus === "form" ? "app-rail__btn--active" : "app-rail__btn--idle"}`}
                onClick={() => scrollToSection("form")}
                aria-current={railFocus === "form" ? "true" : undefined}
              >
                <FormOutlined />
              </button>
            </Tooltip>
          </div>
          <RailSegmentDots count={2} />
          <div className="app-rail__stop">
            <Tooltip title="企业资质信息" placement="right">
              <button
                type="button"
                className={`app-rail__btn ${railFocus === "enterpriseQual" ? "app-rail__btn--active" : "app-rail__btn--idle"}`}
                onClick={() => scrollToSection("enterpriseQual")}
                aria-current={railFocus === "enterpriseQual" ? "true" : undefined}
              >
                <FileProtectOutlined />
              </button>
            </Tooltip>
          </div>
          <RailSegmentDots count={2} />
          <div className="app-rail__stop">
            <Tooltip title="专业资质信息" placement="right">
              <button
                type="button"
                className={`app-rail__btn ${railFocus === "professionalQual" ? "app-rail__btn--active" : "app-rail__btn--idle"}`}
                onClick={() => scrollToSection("professionalQual")}
                aria-current={railFocus === "professionalQual" ? "true" : undefined}
              >
                <SafetyCertificateOutlined />
              </button>
            </Tooltip>
          </div>
        </div>
      </nav>
    </aside>
  );

  return (
    <>
      {typeof document !== "undefined" ? createPortal(railEl, document.body) : null}
      <div className="page-root" ref={pageRootRef}>
      <div ref={headerShellRef} className="page-header-slot">
        <AppHeader sessionId={sessionId} />
      </div>

      <div className="page-shell">
        <div className="page-main" id="main-content">
        <div className="page-main-inner">
          <div className="main-grid">
            <section
              ref={chatSectionRef}
              className="panel chat-column"
            >
              <header className="panel-header">
                <span className="chat-header-title">智能对话</span>
              </header>
              <div className="chat-stream">
                {messages.map((item) => (
                  <div
                    key={item.id}
                    id={`chat-msg-${item.id}`}
                    className={`msg-row msg-${item.role}`}
                  >
                    <div className="msg-stack">
                      <div className="msg-meta">
                        <span>{item.role === "user" ? "用户" : "助手"}</span>
                        <span>{formatClock(item.createdAt)}</span>
                        {item.pending ? (
                          <Badge status="processing" text="生成中" />
                        ) : null}
                        {item.vision?.status === "running" ? (
                          <Badge status="processing" text="识别中" />
                        ) : null}
                      </div>
                      <div
                        className={`msg-bubble ${item.pending ? "msg-bubble--pending" : ""}`}
                      >
                        {item.pending ? (
                          <div className="typing" aria-label="生成中">
                            <span>正在回复</span>
                            <span className="typing-dot" />
                            <span className="typing-dot" />
                            <span className="typing-dot" />
                          </div>
                        ) : item.vision && item.vision.status === "running" ? (
                          <div className="msg-vision">
                            <Progress
                              percent={visionProgressPercent(item.vision)}
                              status="active"
                              size="small"
                              aria-label="识别进度"
                            />
                            <Typography.Text type="secondary" className="msg-vision__hint">
                              {item.vision.label ??
                                (item.vision.fileName
                                  ? `${item.vision.phase} · ${item.vision.fileName}`
                                  : item.vision.phase)}
                            </Typography.Text>
                            <Collapse
                              size="small"
                              className="msg-vision__collapse"
                              items={[
                                {
                                  key: "think",
                                  label: "模型思考 / 中间输出",
                                  children: (
                                    <pre className="msg-vision__pre">
                                      {item.vision.thinkingLog ||
                                      item.vision.assistantLog ? (
                                        <>
                                          {item.vision.thinkingLog ? (
                                            <>
                                              <Typography.Text type="secondary">
                                                【思考】
                                              </Typography.Text>
                                              {"\n"}
                                              {item.vision.thinkingLog}
                                            </>
                                          ) : null}
                                          {item.vision.assistantLog ? (
                                            <>
                                              {"\n\n"}
                                              <Typography.Text type="secondary">
                                                【生成】
                                              </Typography.Text>
                                              {"\n"}
                                              {item.vision.assistantLog}
                                            </>
                                          ) : null}
                                        </>
                                      ) : (
                                        <Typography.Text type="secondary">
                                          等待模型输出…
                                        </Typography.Text>
                                      )}
                                    </pre>
                                  ),
                                },
                              ]}
                            />
                          </div>
                        ) : (
                          item.content
                        )}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </section>

            <section
              ref={formSectionRef}
              className="panel form-panel form-column"
            >
              <div className="panel-body">
                <div className="form-toolbar">
                  <Typography.Title level={5} className="form-title">
                    企业基本信息
                  </Typography.Title>
                  <Space>
                    <Button onClick={onSaveDraft}>暂存草稿</Button>
                    <Button type="primary" onClick={onSubmitForm}>
                      确认提交
                    </Button>
                  </Space>
                </div>
                {ambiguities.length > 0 ? (
                  <Alert
                    type="warning"
                    showIcon
                    className="form-ambiguity-alert"
                    message="需您确认的内容（来自影像识别）"
                    description={
                      <Space direction="vertical" size="middle" style={{ width: "100%" }}>
                        {ambiguities.map((amb) => (
                          <div key={amb.field_key}>
                            <Typography.Paragraph style={{ marginBottom: 8 }}>
                              {amb.question_for_user}
                            </Typography.Paragraph>
                            <Radio.Group
                              onChange={(e) => {
                                const opt = amb.options.find(
                                  (o) => o.option_id === e.target.value,
                                );
                                if (opt) {
                                  form.setFieldsValue({
                                    [amb.field_key]: opt.suggested_value,
                                  } as Partial<FormValues>);
                                  persistForm();
                                }
                                setAmbiguities((prev) =>
                                  prev.filter((a) => a.field_key !== amb.field_key),
                                );
                              }}
                            >
                              <Space direction="vertical">
                                {amb.options.map((o) => (
                                  <Radio key={o.option_id} value={o.option_id}>
                                    {o.label}
                                  </Radio>
                                ))}
                              </Space>
                            </Radio.Group>
                          </div>
                        ))}
                      </Space>
                    }
                  />
                ) : null}
                <Form<FormValues>
                  form={form}
                  layout="vertical"
                  requiredMark
                  initialValues={{
                    safetyLicenseValidityMode: "fixed",
                    transportLicenseValidityMode: "long",
                    qualificationIdDocType: "身份证",
                  }}
                  onValuesChange={() => persistForm()}
                >
                  <Row gutter={24}>
                    <Col xs={24} md={12}>
                      <Form.Item
                        label="公司名称"
                        name="companyName"
                        rules={[{ required: true, message: "请输入公司名称" }]}
                      >
                        <Input placeholder="请输入公司名称" allowClear />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item label="公司曾用名" name="formerName">
                        <Input placeholder="可选" allowClear />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item
                        label="公司简称"
                        name="companyShortName"
                        rules={[{ required: true, message: "请输入公司简称" }]}
                      >
                        <Input placeholder="请输入公司简称" allowClear />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item
                        label={
                          <Space size={4}>
                            <span>企业类型</span>
                            <Tooltip title="请选择与证照一致的企业类型">
                              <QuestionCircleOutlined style={{ color: "rgba(0,0,0,0.45)" }} />
                            </Tooltip>
                          </Space>
                        }
                        name="enterpriseType"
                        rules={[{ required: true, message: "请选择企业类型" }]}
                      >
                        <Select
                          placeholder="请选择"
                          allowClear
                          options={[
                            { value: "有限责任公司", label: "有限责任公司" },
                            { value: "股份有限公司", label: "股份有限公司" },
                            { value: "外商投资企业", label: "外商投资企业" },
                          ]}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item
                        label="企业性质"
                        name="enterpriseNature"
                        rules={[{ required: true, message: "请选择企业性质" }]}
                      >
                        <Select
                          placeholder="请选择"
                          allowClear
                          options={[
                            { value: "国有", label: "国有" },
                            { value: "民营", label: "民营" },
                            { value: "混合所有制", label: "混合所有制" },
                          ]}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item
                        label="注册日期"
                        name="registrationDate"
                        rules={[{ required: true, message: "请选择注册日期" }]}
                      >
                        <DatePicker style={{ width: "100%" }} />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={12}>
                      <Form.Item
                        label="注册资本(万元)"
                        name="registeredCapital"
                        rules={[{ required: true, message: "请输入注册资本" }]}
                      >
                        <Input placeholder="单位：万元" allowClear />
                      </Form.Item>
                    </Col>
                    
                    <Col xs={24} md={12}>
                      <Form.Item
                        label="注册地邮编"
                        name="registeredZip"
                        rules={[{ required: true, message: "请输入邮编" }]}
                      >
                        <Input placeholder="6 位邮编" allowClear maxLength={6} />
                      </Form.Item>
                    </Col>
                  </Row>

                  <div
                    ref={enterpriseQualSectionRef}
                    id="section-enterprise-qual"
                    className="form-scroll-anchor"
                  >
                  <Divider className="form-section-divider" />
                  <Typography.Title level={5} className="form-section-title">
                    企业资质信息
                  </Typography.Title>
                  <Row gutter={24}>
                    <Col span={24}>
                      <Form.Item
                        label="统一社会信用代码"
                        name="unifiedSocialCreditCode"
                        rules={[{ required: true, message: "请输入统一社会信用代码" }]}
                      >
                        <Input placeholder="请输入 18 位统一社会信用代码" allowClear maxLength={18} />
                      </Form.Item>
                    </Col>
                    <Col span={24}>
                      <Form.Item
                        label="证件类型与号码"
                        required
                        tooltip="左侧为证件类型，右侧为证件号码"
                      >
                        <Space.Compact style={{ width: "100%" }}>
                          <Form.Item
                            name="qualificationIdDocType"
                            noStyle
                            rules={[{ required: true, message: "请选择证件类型" }]}
                          >
                            <Select
                              placeholder="证件类型"
                              style={{ width: 120 }}
                              options={[
                                { value: "身份证", label: "身份证" },
                                { value: "护照", label: "护照" },
                                { value: "港澳居民来往内地通行证", label: "港澳居民来往内地通行证" },
                              ]}
                            />
                          </Form.Item>
                          <Form.Item
                            name="qualificationIdDocNumber"
                            noStyle
                            rules={[{ required: true, message: "请输入证件号码" }]}
                          >
                            <Input style={{ width: "100%" }} placeholder="请输入证件号码" allowClear />
                          </Form.Item>
                        </Space.Compact>
                      </Form.Item>
                    </Col>
                  </Row>
                  </div>

                  <div
                    ref={professionalQualSectionRef}
                    id="section-professional-qual"
                    className="form-scroll-anchor"
                  >
                  <Divider className="form-section-divider" />
                  <Typography.Title level={5} className="form-section-title">
                    专业资质信息
                  </Typography.Title>
                  <Typography.Paragraph type="secondary" className="form-section-lead">
                    请按许可证件如实填写下列字段；许可证件中的企业名称应与营业执照保持一致。资质扫描件可通过底部对话区上传，由助手协助处理。
                  </Typography.Paragraph>

                  <div className="form-subsection">
                    <div className="form-subsection__head">
                    
                      <Typography.Text strong className="form-subsection__title">
                        危险品经营许可证
                      </Typography.Text>
                    </div>
                    <Row gutter={16} className="form-qualification-row">
                      <Col xs={24} lg={12}>
                        <Form.Item
                          label="行政许可名称"
                          name="safetyAdminLicenseName"
                          rules={[{ required: true, message: "请填写相关行政许可名称" }]}
                        >
                          <Input placeholder="请填写相关行政许可名称" allowClear />
                        </Form.Item>
                        <Form.Item
                          label="编号"
                          name="safetyLicenseNo"
                          rules={[{ required: true, message: "请填写编号" }]}
                        >
                          <Input placeholder="请填写编号" allowClear />
                        </Form.Item>
                        <Form.Item label="有效期" required>
                          <Form.Item name="safetyLicenseValidityMode" noStyle>
                            <Radio.Group>
                              <Radio.Button value="fixed">固定日期</Radio.Button>
                              <Radio.Button value="long">长期有效</Radio.Button>
                            </Radio.Group>
                          </Form.Item>
                          <div className="form-nested-field">
                            <Form.Item
                              name="safetyLicenseValidityRange"
                              noStyle
                              rules={
                                (safetyLicenseValidityMode ?? "fixed") === "fixed"
                                  ? [
                                      { required: true, message: "请选择有效期起止日期" },
                                      {
                                        validator: async (_, value) => {
                                          if (
                                            !value ||
                                            !Array.isArray(value) ||
                                            value.length !== 2 ||
                                            !value[0] ||
                                            !value[1]
                                          ) {
                                            return Promise.reject(new Error("请选择完整的起止日期"));
                                          }
                                          return Promise.resolve();
                                        },
                                      },
                                    ]
                                  : []
                              }
                            >
                              <DatePicker.RangePicker
                                style={{ width: "100%", marginTop: 8 }}
                                placeholder={["开始日期", "结束日期"]}
                                disabled={(safetyLicenseValidityMode ?? "fixed") === "long"}
                              />
                            </Form.Item>
                          </div>
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={12}>
                        <Form.Item
                          label="发证机构"
                          name="safetyIssuingAuthority"
                          rules={[{ required: true, message: "请填写发证机构" }]}
                        >
                          <Input placeholder="请填写发证机构" allowClear />
                        </Form.Item>
                        <Form.Item
                          label="法定代表人（负责人）"
                          name="safetyLegalRepresentative"
                          rules={[{ required: true, message: "请填写法定代表人（负责人）" }]}
                        >
                          <Input placeholder="请填写法定代表人（负责人）" allowClear />
                        </Form.Item>
                      </Col>
                    </Row>
                  </div>

                  <div className="form-subsection form-subsection--spaced">
                    <Typography.Text strong className="form-subsection__title">
                      危化品运输许可证
                    </Typography.Text>
                    <Row gutter={16} className="form-qualification-row">
                      <Col xs={24} lg={12}>
                        <Form.Item label="行政许可名称" name="transportAdminLicenseName">
                          <Input placeholder="请填写相关行政许可名称" allowClear />
                        </Form.Item>
                        <Form.Item label="编号" name="transportLicenseNo">
                          <Input placeholder="请填写编号" allowClear />
                        </Form.Item>
                        <Form.Item label="有效期">
                          <Form.Item name="transportLicenseValidityMode" noStyle>
                            <Radio.Group>
                              <Radio.Button value="fixed">固定日期</Radio.Button>
                              <Radio.Button value="long">长期有效</Radio.Button>
                            </Radio.Group>
                          </Form.Item>
                          <div className="form-nested-field">
                            <Form.Item
                              name="transportLicenseValidityRange"
                              noStyle
                              rules={
                                (transportLicenseValidityMode ?? "long") === "fixed"
                                  ? [
                                      { required: true, message: "请选择有效期起止日期" },
                                      {
                                        validator: async (_, value) => {
                                          if (
                                            !value ||
                                            !Array.isArray(value) ||
                                            value.length !== 2 ||
                                            !value[0] ||
                                            !value[1]
                                          ) {
                                            return Promise.reject(new Error("请选择完整的起止日期"));
                                          }
                                          return Promise.resolve();
                                        },
                                      },
                                    ]
                                  : []
                              }
                            >
                              <DatePicker.RangePicker
                                style={{ width: "100%", marginTop: 8 }}
                                placeholder={["开始日期", "结束日期"]}
                                disabled={(transportLicenseValidityMode ?? "long") === "long"}
                              />
                            </Form.Item>
                          </div>
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={12}>
                        <Form.Item label="发证机构" name="transportIssuingAuthority">
                          <Input placeholder="请填写发证机构" allowClear />
                        </Form.Item>
                      </Col>
                    </Row>
                  </div>
                  </div>
                </Form>
              </div>
            </section>
          </div>
        </div>
        </div>
      </div>

      <footer className="composer-dock">
        <input
          ref={fileInputRef}
          type="file"
          className="visually-hidden"
          accept="image/*"
          multiple
          disabled={visionBusy}
          onChange={(e) => void onFileInputChange(e)}
        />
        <div className="composer-dock-inner">
          <Input.TextArea
            className="composer-textarea"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault();
                void sendChat();
              }
            }}
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            autoSize={{ minRows: 1, maxRows: 5 }}
            disabled={sending}
          />
          <div className="composer-actions">
            <Tooltip title="支持一次选择多个文件">
              <Button
                type="link"
                className="composer-upload-link"
                icon={<CloudUploadOutlined />}
                onClick={onPickFiles}
              >
                上传文件
              </Button>
            </Tooltip>
            <Button
              type="primary"
              icon={<SendOutlined />}
              loading={sending}
              onClick={() => void sendChat()}
            >
              发送
            </Button>
          </div>
        </div>
      </footer>
    </div>
    </>
  );
}
