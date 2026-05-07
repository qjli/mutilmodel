const BASE = "";

export type ProblemBody = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
};

export async function readApiError(res: Response): Promise<string> {
  const text = await res.text();
  try {
    const j = JSON.parse(text) as ProblemBody;
    if (j.detail) {
      return j.detail;
    }
    if (j.title) {
      return j.title;
    }
  } catch {
    /* ignore */
  }
  return text || res.statusText;
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error(await readApiError(res));
  }
  return (await res.json()) as T;
}

export async function postMultipart<T>(path: string, file: File): Promise<T> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch(`${BASE}${path}`, { method: "POST", body: fd });
  if (!res.ok) {
    throw new Error(await readApiError(res));
  }
  return (await res.json()) as T;
}

export async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) {
    throw new Error(await readApiError(res));
  }
  return (await res.json()) as T;
}

/** SSE payload keys follow backend JSON (snake_case for nested DTO fields). */
export type VisionAmbiguousOption = {
  option_id: string;
  label: string;
  suggested_value: string;
};

export type VisionAmbiguousField = {
  field_key: string;
  question_for_user: string;
  options: VisionAmbiguousOption[];
};

export type VisionSseEvent =
  | {
      type: "progress";
      phase: string;
      done: number;
      total: number;
      label?: string;
      fileName?: string;
    }
  | { type: "thinking"; delta: string }
  | { type: "assistant_text"; delta: string }
  | {
      type: "result";
      reply: string;
      formPatch: Record<string, unknown>;
      ambiguities: VisionAmbiguousField[];
    }
  | { type: "done" }
  | { type: "error"; message: string };

async function consumeSseJson(
  body: ReadableStream<Uint8Array>,
  onEvent: (ev: VisionSseEvent) => void,
): Promise<void> {
  const reader = body.getReader();
  const dec = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += dec.decode(value, { stream: true });
    let sep: number;
    while ((sep = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      for (const rawLine of block.split("\n")) {
        const line = rawLine.replace(/\r$/, "");
        if (!line.startsWith("data:")) {
          continue;
        }
        const json = line.slice(5).trim();
        if (!json) {
          continue;
        }
        onEvent(JSON.parse(json) as VisionSseEvent);
      }
    }
  }
}

/**
 * Upload multiple images; consumes Server-Sent Events with JSON `data:` frames.
 */
export async function postVisionFormStream(
  sessionId: string,
  files: File[],
  onEvent: (ev: VisionSseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const fd = new FormData();
  for (const f of files) {
    fd.append("files", f);
  }
  const res = await fetch(
    `${BASE}/api/sessions/${encodeURIComponent(sessionId)}/vision/form-stream`,
    {
      method: "POST",
      body: fd,
      headers: { Accept: "text/event-stream" },
      signal,
    },
  );
  if (!res.ok) {
    throw new Error(await readApiError(res));
  }
  if (!res.body) {
    throw new Error("响应无流式正文");
  }
  await consumeSseJson(res.body, onEvent);
}
