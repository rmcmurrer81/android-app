const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
const MAX_REQUEST_BYTES = 6 * 1024 * 1024;
const MAX_HISTORY_MESSAGES = 24;
const MAX_TEXT_CHARS = 40_000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({
        ok: Boolean(env.OPENAI_API_KEY && env.SARAH_BACKEND_TOKEN),
        service: "sarah-model-proxy",
        provider: "openai",
        model_override: cleanModel(env.SARAH_OPENAI_MODEL) || null,
        online: true,
      }, 200);
    }

    if (request.method === "GET") {
      return json({
        service: "sarah-model-proxy",
        status: "ready",
        health: "/health",
      }, 200);
    }

    if (request.method !== "POST") {
      return json({ error: "method_not_allowed" }, 405, { Allow: "GET, POST" });
    }

    if (!env.OPENAI_API_KEY || !env.SARAH_BACKEND_TOKEN) {
      return json({ error: "server_not_configured" }, 503);
    }

    const suppliedToken = bearerToken(request.headers.get("Authorization"));
    if (!suppliedToken || !(await constantTimeEqual(suppliedToken, env.SARAH_BACKEND_TOKEN))) {
      return json({ error: "unauthorized" }, 401);
    }

    const contentLength = Number(request.headers.get("Content-Length") || 0);
    if (Number.isFinite(contentLength) && contentLength > MAX_REQUEST_BYTES) {
      return json({ error: "request_too_large" }, 413);
    }

    let raw;
    try {
      raw = await request.text();
    } catch {
      return json({ error: "request_read_failed" }, 400);
    }
    if (new TextEncoder().encode(raw).byteLength > MAX_REQUEST_BYTES) {
      return json({ error: "request_too_large" }, 413);
    }

    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    const provider = String(body.provider || "openai").trim().toLowerCase();
    if (provider !== "openai") {
      return json({ error: "unsupported_provider", provider }, 400);
    }

    const message = boundedText(body.message, MAX_TEXT_CHARS);
    if (!message) return json({ error: "message_required" }, 400);

    const requestedModel = cleanModel(body.model);
    const model = cleanModel(env.SARAH_OPENAI_MODEL) || requestedModel || "gpt-5.1";
    const instructions = boundedText(body.system_prompt, MAX_TEXT_CHARS);
    const history = Array.isArray(body.history)
      ? body.history.slice(-MAX_HISTORY_MESSAGES)
      : [];

    const input = [];
    for (let index = 0; index < history.length; index += 1) {
      const row = history[index];
      const role = row && row.role === "assistant" ? "assistant" : "user";
      const content = boundedText(row && row.content, MAX_TEXT_CHARS);
      const duplicatesCurrentMessage = index === history.length - 1
        && role === "user"
        && content === message;
      if (content && !duplicatesCurrentMessage) input.push({ role, content });
    }

    const currentContent = [{ type: "input_text", text: message }];
    const imageBase64 = typeof body.image_jpeg_base64 === "string"
      ? body.image_jpeg_base64.trim()
      : "";
    if (imageBase64) {
      if (imageBase64.length > 5_600_000 || !/^[A-Za-z0-9+/=\r\n]+$/.test(imageBase64)) {
        return json({ error: "invalid_or_oversized_image" }, 413);
      }
      currentContent.push({
        type: "input_image",
        image_url: `data:image/jpeg;base64,${imageBase64.replace(/\s+/g, "")}`,
        detail: "auto",
      });
    }
    input.push({ role: "user", content: currentContent });

    const payload = {
      model,
      store: false,
      instructions,
      input,
      max_output_tokens: maxOutputTokens(env.SARAH_MAX_OUTPUT_TOKENS),
    };
    if (body.web_search === true) {
      payload.tools = [{ type: "web_search" }];
    }

    let upstream;
    try {
      upstream = await fetch(OPENAI_RESPONSES_URL, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.OPENAI_API_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      });
    } catch {
      return json({ error: "openai_unreachable" }, 502);
    }

    const upstreamText = await upstream.text();
    let upstreamJson = null;
    try {
      upstreamJson = JSON.parse(upstreamText);
    } catch {
      // A non-JSON upstream response is handled below without exposing it.
    }

    if (!upstream.ok) {
      const upstreamMessage = boundedText(
        upstreamJson && upstreamJson.error && upstreamJson.error.message,
        400,
      );
      return json({
        error: "openai_error",
        upstream_status: upstream.status,
        message: upstreamMessage || "The model provider rejected the request.",
      }, upstream.status >= 400 && upstream.status < 500 ? upstream.status : 502);
    }

    const reply = extractReply(upstreamJson);
    if (!reply) {
      return json({ error: "empty_model_reply" }, 502);
    }

    return json({
      reply,
      provider: "openai",
      model,
      online: true,
      request_id: upstream.headers.get("x-request-id") || undefined,
    }, 200);
  },
};

function extractReply(response) {
  if (!response || typeof response !== "object") return "";
  const top = boundedText(response.output_text, MAX_TEXT_CHARS);
  if (top) return top;

  const pieces = [];
  if (Array.isArray(response.output)) {
    for (const item of response.output) {
      if (!item || item.type !== "message" || !Array.isArray(item.content)) continue;
      for (const part of item.content) {
        if (!part || part.type !== "output_text") continue;
        const text = boundedText(part.text, MAX_TEXT_CHARS);
        if (text) pieces.push(text);
      }
    }
  }
  return pieces.join("\n").trim();
}

function boundedText(value, limit) {
  if (value === null || value === undefined) return "";
  const text = String(value).trim();
  return text.length > limit ? text.slice(0, limit) : text;
}

function cleanModel(value) {
  const model = boundedText(value, 80);
  return /^[A-Za-z0-9._:-]+$/.test(model) ? model : "";
}

function maxOutputTokens(value) {
  const parsed = Number.parseInt(String(value || "1200"), 10);
  if (!Number.isFinite(parsed)) return 1200;
  return Math.max(200, Math.min(parsed, 8000));
}

function bearerToken(header) {
  const match = /^Bearer\s+(.+)$/i.exec(String(header || "").trim());
  return match ? match[1].trim() : "";
}

async function constantTimeEqual(left, right) {
  const encoder = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(String(left))),
    crypto.subtle.digest("SHA-256", encoder.encode(String(right))),
  ]);
  const av = new Uint8Array(a);
  const bv = new Uint8Array(b);
  let difference = av.length ^ bv.length;
  for (let i = 0; i < Math.max(av.length, bv.length); i += 1) {
    difference |= (av[i] || 0) ^ (bv[i] || 0);
  }
  return difference === 0;
}

function json(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}
