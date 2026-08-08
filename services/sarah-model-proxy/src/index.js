const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
const TAVILY_SEARCH_URL = "https://api.tavily.com/search";
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

    if (request.method === "GET"
        && (url.pathname === "/health" || url.pathname === "/capabilities")) {
      if (url.pathname === "/capabilities") {
        if (!env.SARAH_BACKEND_TOKEN) {
          return json({ error: "server_not_configured" }, 503);
        }
        const suppliedToken = bearerToken(request.headers.get("Authorization"));
        if (!suppliedToken || !(await constantTimeEqual(suppliedToken, env.SARAH_BACKEND_TOKEN))) {
          return json({ error: "unauthorized" }, 401);
        }
      }
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
        current_source_ready: provider === "openai"
          ? Boolean(env.OPENAI_API_KEY)
          : Boolean(env.TAVILY_API_KEY),
        voice_ready: Boolean(env.ELEVENLABS_API_KEY && cleanVoiceId(env.SARAH_ELEVENLABS_VOICE_ID)),
        route_rate_limits_ready: Boolean(
          env.MODEL_RATE_LIMITER && env.SEARCH_RATE_LIMITER && env.VOICE_RATE_LIMITER
        ),
        online: true,
      }, 200);
    }

    if (request.method === "GET") {
      return json({
        service: "sarah-model-proxy",
        status: "ready",
        health: "/health",
        capabilities: "/capabilities",
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
      const limited = await routeRateLimit(env, "VOICE_RATE_LIMITER", "voice");
      if (limited) return limited;
      return runElevenLabs(env, body);
    }
    if (url.pathname === "/search") {
      const limited = await routeRateLimit(env, "SEARCH_RATE_LIMITER", "search");
      if (limited) return limited;
      return runProtectedSearch(env, body);
    }
    if (url.pathname !== "/") {
      return json({ error: "not_found" }, 404);
    }
    const limited = await routeRateLimit(env, "MODEL_RATE_LIMITER", "conversation");
    if (limited) return limited;

    const requestedProvider = cleanProvider(body.provider);
    const selectedProvider = cleanProvider(env.SARAH_MODEL_PROVIDER)
      || requestedProvider
      || (env.AI ? "workers-ai" : "openai");
    if (!providerReady(selectedProvider, env)) {
      return json({ error: "provider_not_configured", provider: selectedProvider }, 503);
    }

    const message = boundedText(body.message, MAX_TEXT_CHARS);
    if (!message) return json({ error: "message_required" }, 400);
    const searchQuery = boundedText(body.search_query, 2_000) || message;

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
      const webEvidence = body.web_search === true && env.TAVILY_API_KEY
        ? await runTavilySearch(env, searchQuery)
        : emptyWebEvidence();
      return runWorkersAi(env, {
        model,
        instructions,
        history,
        message,
        imageBase64,
        webSearchRequested: body.web_search === true,
        searchQuery,
        webEvidence,
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
        searchQuery,
      });
    }
    return json({ error: "unsupported_provider", provider: selectedProvider }, 400);
  },
};

async function routeRateLimit(env, bindingName, route) {
  const binding = env[bindingName];
  // Source-only unit fixtures have no deployment identity. Every exact
  // accepted candidate is identity-bound and therefore fails closed if a
  // declared limiter is absent at runtime.
  if (!binding || typeof binding.limit !== "function") {
    if (deploymentIdentity(env).ready) {
      return json({ error: "rate_limiter_unavailable", route }, 503);
    }
    return null;
  }
  try {
    const result = await binding.limit({ key: `sarah-r2-candidate:${route}` });
    if (result && result.success) return null;
    return json({ error: "rate_limited", route }, 429, { "Retry-After": "60" });
  } catch {
    return json({ error: "rate_limiter_unavailable", route }, 503);
  }
}

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

async function runProtectedSearch(env, body) {
  if (!env.TAVILY_API_KEY) {
    return json({ error: "current_source_not_configured" }, 503);
  }
  const query = boundedText(body.query, 2_000);
  if (!query) return json({ error: "query_required" }, 400);
  const limit = Math.max(1, Math.min(8, Number(body.max_results) || 5));
  const evidence = await runTavilySearch(env, query, limit);
  if (!evidence.applied) {
    return json({ error: "current_source_unavailable" }, 502);
  }
  return json({
    provider: "tavily",
    web_search_applied: true,
    web_search_request_id: evidence.requestId || undefined,
    results: evidence.results,
  }, 200);
}

async function runWorkersAi(env, request) {
  const messages = [];
  let instructions = request.instructions;
  if (request.webSearchRequested && request.webEvidence.applied) {
    instructions = `${instructions}\nCURRENT SOURCE EVIDENCE (Tavily basic search; use only these snippets and preserve uncertainty):\n${request.webEvidence.context}`.trim();
  } else if (request.webSearchRequested) {
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
    web_search_requested: request.webSearchRequested,
    web_search_applied: request.webEvidence.applied,
    source_urls: request.webEvidence.sourceUrls,
    web_search_provider: request.webEvidence.applied ? "tavily" : null,
    web_search_request_id: request.webEvidence.requestId || undefined,
  }, 200);
}

async function runTavilySearch(env, query, requestedLimit = 5) {
  try {
    const upstream = await fetch(TAVILY_SEARCH_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.TAVILY_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        query,
        search_depth: "basic",
        max_results: Math.max(1, Math.min(8, Number(requestedLimit) || 5)),
        include_answer: false,
        include_raw_content: false,
        include_images: false,
      }),
    });
    if (!upstream.ok) return emptyWebEvidence();
    const data = await upstream.json();
    const results = Array.isArray(data && data.results) ? data.results : [];
    const sourceUrls = [];
    const evidenceRows = [];
    const safeResults = [];
    for (const result of results.slice(0, Math.max(1, Math.min(8, Number(requestedLimit) || 5)))) {
      const url = boundedText(result && result.url, 2_000);
      const title = boundedText(result && result.title, 240);
      const content = boundedText(result && result.content, 1_200);
      if (!url.startsWith("https://") || !content) continue;
      if (!sourceUrls.includes(url)) sourceUrls.push(url);
      evidenceRows.push(`[${evidenceRows.length + 1}] ${title || "Source"}\nURL: ${url}\nSnippet: ${content}`);
      safeResults.push({ title: title || "Possible travel match", url, summary: content });
    }
    if (sourceUrls.length === 0 || evidenceRows.length === 0) return emptyWebEvidence();
    return {
      applied: true,
      sourceUrls,
      context: evidenceRows.join("\n\n"),
      requestId: boundedText(data && data.request_id, 160),
      results: safeResults,
    };
  } catch {
    return emptyWebEvidence();
  }
}

function emptyWebEvidence() {
  return { applied: false, sourceUrls: [], context: "", requestId: "", results: [] };
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

  const sourceContext = request.webSearchRequested && request.searchQuery
    ? `\nCURRENT SOURCE SEARCH CONTEXT: ${request.searchQuery}`
    : "";
  const payload = {
    model: request.model,
    store: false,
    instructions: `${request.instructions || ""}${sourceContext}`.trim(),
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
  const webEvidence = extractOpenAiWebEvidence(upstreamJson, request.webSearchRequested);
  return json({
    reply,
    provider: "openai",
    model: request.model,
    online: true,
    web_search_requested: request.webSearchRequested,
    web_search_applied: webEvidence.applied,
    source_urls: webEvidence.sourceUrls,
    request_id: upstream.headers.get("x-request-id") || undefined,
  }, 200);
}

function extractOpenAiWebEvidence(response, requested) {
  if (!requested || !response || !Array.isArray(response.output)) {
    return { applied: false, sourceUrls: [] };
  }
  const completedCalls = response.output.filter((item) => item
    && item.type === "web_search_call"
    && item.status === "completed");
  if (completedCalls.length === 0) {
    return { applied: false, sourceUrls: [] };
  }
  const urls = [];
  for (const item of response.output) {
    if (!item || item.type !== "message" || !Array.isArray(item.content)) continue;
    for (const content of item.content) {
      if (!content || !Array.isArray(content.annotations)) continue;
      for (const annotation of content.annotations) {
        if (annotation && annotation.type === "url_citation") {
          addHttpsUrl(annotation.url, urls);
        }
      }
    }
  }
  // Some Responses API web-search calls expose the actual tool sources directly.
  // Inspect only that explicit evidence field, never arbitrary URL-looking model output.
  for (const call of completedCalls) {
    const sources = call.action && Array.isArray(call.action.sources)
      ? call.action.sources
      : [];
    for (const source of sources) addHttpsUrl(source && source.url, urls);
  }
  return { applied: completedCalls.length > 0 && urls.length > 0, sourceUrls: urls.slice(0, 20) };
}

function addHttpsUrl(value, output) {
  if (typeof value !== "string" || !value.startsWith("https://") || output.length >= 20) return;
  if (!output.includes(value)) output.push(value);
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
