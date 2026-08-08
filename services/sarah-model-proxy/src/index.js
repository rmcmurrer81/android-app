const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
const WORKERS_AI_DEFAULT_MODEL = "@cf/google/gemma-4-26b-a4b-it";
const OPENAI_DEFAULT_MODEL = "gpt-5.1";
const WORKER_CONTRACT_VERSION = "sarah-model-proxy-v2-workers-ai-voice";
const MAX_REQUEST_BYTES = 6 * 1024 * 1024;
const MAX_HISTORY_MESSAGES = 24;
const MAX_TEXT_CHARS = 40_000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const provider = configuredProvider(env);

    if (request.method === "GET" && url.pathname === "/health") {
      const deployment = deploymentIdentity(env);
      return json({
        ok: Boolean(env.SARAH_BACKEND_TOKEN && providerReady(provider, env)),
        service: "sarah-model-proxy",
        contract_version: WORKER_CONTRACT_VERSION,
        deployment_ready: deployment.ready,
        deployment_id: deployment.id || null,
        source_sha256: deployment.sourceSha256 || null,
        config_sha256: deployment.configSha256 || null,
        provider,
        model_override: configuredModel(provider, env) || null,
        voice_ready: Boolean(env.ELEVENLABS_API_KEY && cleanVoiceId(env.SARAH_ELEVENLABS_VOICE_ID)),
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

    if (!env.SARAH_BACKEND_TOKEN) {
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

    if (url.pathname === "/voice") {
      return runElevenLabs(env, body);
    }
    if (url.pathname !== "/") {
      return json({ error: "not_found" }, 404);
    }

    const requestedProvider = cleanProvider(body.provider);
    const selectedProvider = cleanProvider(env.SARAH_MODEL_PROVIDER)
      || requestedProvider
      || (env.AI ? "workers-ai" : "openai");
    if (!providerReady(selectedProvider, env)) {
      return json({ error: "provider_not_configured", provider: selectedProvider }, 503);
    }

    const message = boundedText(body.message, MAX_TEXT_CHARS);
    if (!message) return json({ error: "message_required" }, 400);

    const requestedModel = cleanModel(body.model);
    const model = configuredModel(selectedProvider, env)
      || requestedModel
      || defaultModel(selectedProvider);
    const instructions = boundedText(body.system_prompt || body.system, MAX_TEXT_CHARS);
    const history = normalizeHistory(body.history, message);
    const imageBase64 = validImageBase64(body.image_jpeg_base64);
    if (imageBase64 === null) {
      return json({ error: "invalid_or_oversized_image" }, 413);
    }

    if (selectedProvider === "workers-ai") {
      return runWorkersAi(env, {
        model,
        instructions,
        history,
        message,
        imageBase64,
        webSearchRequested: body.web_search === true,
      });
    }
    if (selectedProvider === "openai") {
      return runOpenAi(env, {
        model,
        instructions,
        history,
        message,
        imageBase64,
        webSearchRequested: body.web_search === true,
      });
    }
    return json({ error: "unsupported_provider", provider: selectedProvider }, 400);
  },
};

async function runElevenLabs(env, body) {
  const configuredVoice = cleanVoiceId(env.SARAH_ELEVENLABS_VOICE_ID);
  if (!env.ELEVENLABS_API_KEY || !configuredVoice) {
    return json({ error: "voice_not_configured" }, 503);
  }
  const requestedVoice = cleanVoiceId(body.voice_id);
  if (requestedVoice && requestedVoice !== configuredVoice) {
    return json({ error: "voice_not_approved" }, 403);
  }
  const text = boundedText(body.text, 9_000);
  if (!text) return json({ error: "voice_text_required" }, 400);

  const model = cleanModel(env.SARAH_ELEVENLABS_MODEL_ID)
    || cleanModel(body.model_id)
    || "eleven_flash_v2_5";
  const endpoint = `https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(configuredVoice)}/stream?output_format=mp3_44100_128`;
  let upstream;
  try {
    upstream = await fetch(endpoint, {
      method: "POST",
      headers: {
        "xi-api-key": env.ELEVENLABS_API_KEY,
        Accept: "audio/mpeg",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        text,
        model_id: model,
        voice_settings: {
          stability: boundedNumber(body.voice_settings && body.voice_settings.stability, 0.5, 0, 1),
          similarity_boost: boundedNumber(body.voice_settings && body.voice_settings.similarity_boost, 0.75, 0, 1),
          style: boundedNumber(body.voice_settings && body.voice_settings.style, 0, 0, 1),
          speed: boundedNumber(body.voice_settings && body.voice_settings.speed, 1, 0.7, 1.2),
          use_speaker_boost: true,
        },
        apply_text_normalization: "auto",
      }),
    });
  } catch {
    return json({ error: "elevenlabs_unreachable" }, 502);
  }

  if (!upstream.ok) {
    let message = "ElevenLabs rejected the voice request.";
    try {
      const data = await upstream.json();
      message = boundedText(
        data && data.detail && (data.detail.message || data.detail),
        300,
      ) || message;
    } catch {
      // Keep the bounded generic error.
    }
    return json({
      error: "elevenlabs_error",
      upstream_status: upstream.status,
      message,
    }, upstream.status >= 400 && upstream.status < 500 ? upstream.status : 502);
  }

  return new Response(upstream.body, {
    status: 200,
    headers: {
      "Content-Type": upstream.headers.get("Content-Type") || "audio/mpeg",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "X-Sarah-Voice-Route": "elevenlabs-protected",
    },
  });
}

async function runWorkersAi(env, request) {
  const messages = [];
  let instructions = request.instructions;
  if (request.webSearchRequested) {
    instructions = `${instructions}\nNo live web-search result is attached to this model request. Do not claim current research, prices, availability, or a completed booking.`.trim();
  }
  if (instructions) messages.push({ role: "system", content: instructions });
  messages.push(...request.history);

  let currentContent = request.message;
  if (request.imageBase64) {
    currentContent = [
      { type: "text", text: request.message },
      {
        type: "image_url",
        image_url: { url: `data:image/jpeg;base64,${request.imageBase64}` },
      },
    ];
  }
  messages.push({ role: "user", content: currentContent });

  let upstream;
  try {
    upstream = await env.AI.run(request.model, {
      messages,
      max_tokens: maxOutputTokens(env.SARAH_MAX_OUTPUT_TOKENS),
    });
  } catch (error) {
    return json({
      error: "workers_ai_error",
      message: boundedText(error && error.message, 400) || "Workers AI rejected the request.",
    }, 502);
  }

  const reply = extractWorkersAiReply(upstream);
  if (!reply) return json({ error: "empty_model_reply" }, 502);
  return json({
    reply,
    provider: "workers-ai",
    model: request.model,
    online: true,
    web_search_applied: false,
  }, 200);
}

async function runOpenAi(env, request) {
  const input = [];
  for (const row of request.history) input.push(row);

  const currentContent = [{ type: "input_text", text: request.message }];
  if (request.imageBase64) {
    currentContent.push({
      type: "input_image",
      image_url: `data:image/jpeg;base64,${request.imageBase64}`,
      detail: "auto",
    });
  }
  input.push({ role: "user", content: currentContent });

  const payload = {
    model: request.model,
    store: false,
    instructions: request.instructions,
    input,
    max_output_tokens: maxOutputTokens(env.SARAH_MAX_OUTPUT_TOKENS),
  };
  if (request.webSearchRequested) payload.tools = [{ type: "web_search" }];

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
    // A non-JSON upstream response is handled without exposing it.
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

  const reply = extractOpenAiReply(upstreamJson);
  if (!reply) return json({ error: "empty_model_reply" }, 502);
  return json({
    reply,
    provider: "openai",
    model: request.model,
    online: true,
    request_id: upstream.headers.get("x-request-id") || undefined,
  }, 200);
}

function normalizeHistory(value, currentMessage) {
  if (!Array.isArray(value)) return [];
  const rows = [];
  for (const row of value.slice(-MAX_HISTORY_MESSAGES)) {
    const role = row && row.role === "assistant" ? "assistant" : "user";
    const content = boundedText(row && row.content, MAX_TEXT_CHARS);
    if (content) rows.push({ role, content });
  }
  const last = rows.at(-1);
  if (last && last.role === "user" && last.content === currentMessage) rows.pop();
  return rows;
}

function validImageBase64(value) {
  if (value === null || value === undefined || value === "") return "";
  if (typeof value !== "string") return null;
  const image = value.trim();
  if (image.length > 5_600_000 || !/^[A-Za-z0-9+/=\r\n]+$/.test(image)) return null;
  return image.replace(/\s+/g, "");
}

function extractWorkersAiReply(response) {
  if (typeof response === "string") return boundedText(response, MAX_TEXT_CHARS);
  if (!response || typeof response !== "object") return "";
  const choices = Array.isArray(response.choices)
    ? response.choices
    : (response.result && Array.isArray(response.result.choices) ? response.result.choices : []);
  const firstChoice = choices[0];
  const choiceText = firstChoice && firstChoice.message
    ? textContent(firstChoice.message.content)
    : boundedText(firstChoice && firstChoice.text, MAX_TEXT_CHARS);
  return boundedText(
    response.response
      || response.output_text
      || (response.result && (response.result.response || response.result.output_text))
      || choiceText,
    MAX_TEXT_CHARS,
  );
}

function textContent(value) {
  if (typeof value === "string") return boundedText(value, MAX_TEXT_CHARS);
  if (!Array.isArray(value)) return "";
  return value
    .map((part) => boundedText(part && (part.text || part.content), MAX_TEXT_CHARS))
    .filter(Boolean)
    .join("\n")
    .trim()
    .slice(0, MAX_TEXT_CHARS);
}

function extractOpenAiReply(response) {
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
  const model = boundedText(value, 120);
  return /^[@A-Za-z0-9][@A-Za-z0-9._:/-]*$/.test(model) ? model : "";
}

function cleanProvider(value) {
  const provider = boundedText(value, 40).toLowerCase();
  if (provider === "workers-ai" || provider === "cloudflare" || provider === "cloudflare-workers-ai") {
    return "workers-ai";
  }
  return provider === "openai" ? "openai" : "";
}

function cleanVoiceId(value) {
  const voice = boundedText(value, 80);
  return /^[A-Za-z0-9_-]+$/.test(voice) ? voice : "";
}

function boundedNumber(value, fallback, minimum, maximum) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(minimum, Math.min(parsed, maximum));
}

function configuredProvider(env) {
  return cleanProvider(env.SARAH_MODEL_PROVIDER) || (env.AI ? "workers-ai" : "openai");
}

function configuredModel(provider, env) {
  return cleanModel(env.SARAH_MODEL_ID)
    || (provider === "openai" ? cleanModel(env.SARAH_OPENAI_MODEL) : "");
}

function defaultModel(provider) {
  return provider === "workers-ai" ? WORKERS_AI_DEFAULT_MODEL : OPENAI_DEFAULT_MODEL;
}

function providerReady(provider, env) {
  if (provider === "workers-ai") return Boolean(env.AI);
  if (provider === "openai") return Boolean(env.OPENAI_API_KEY);
  return false;
}

function deploymentIdentity(env) {
  const id = cleanHex(env.SARAH_DEPLOYMENT_ID, 40);
  const sourceSha256 = cleanHex(env.SARAH_SOURCE_SHA256, 64);
  const configSha256 = cleanHex(env.SARAH_CONFIG_SHA256, 64);
  return {
    ready: Boolean(id && sourceSha256 && configSha256),
    id,
    sourceSha256,
    configSha256,
  };
}

function cleanHex(value, length) {
  const text = value === null || value === undefined ? "" : String(value).trim().toLowerCase();
  return new RegExp(`^[a-f0-9]{${length}}$`).test(text) ? text : "";
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
