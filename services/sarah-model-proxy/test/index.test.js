import assert from "node:assert/strict";
import test from "node:test";

import worker from "../src/index.js";

const TOKEN = "test-only-sarah-token";

function request(body, token = TOKEN, path = "/") {
  return new Request(`https://sarah.example${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

test("Workers AI health reports the configured provider without exposing secrets", async () => {
  const deploymentId = "a".repeat(40);
  const sourceSha256 = "b".repeat(64);
  const configSha256 = "c".repeat(64);
  const env = {
    AI: { run: async () => ({ response: "unused" }) },
    MODEL_RATE_LIMITER: { limit: async () => ({ success: true }) },
    SEARCH_RATE_LIMITER: { limit: async () => ({ success: true }) },
    VOICE_RATE_LIMITER: { limit: async () => ({ success: true }) },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    SARAH_MODEL_ID: "@cf/google/gemma-4-26b-a4b-it",
    SARAH_DEPLOYMENT_ID: deploymentId,
    SARAH_SOURCE_SHA256: sourceSha256,
    SARAH_CONFIG_SHA256: configSha256,
  };
  const response = await worker.fetch(new Request("https://sarah.example/health"), env);
  const data = await response.json();
  assert.equal(response.status, 200);
  assert.equal(data.ok, true);
  assert.equal(data.contract_version, "sarah-model-proxy-v2-workers-ai-voice");
  assert.equal(data.deployment_ready, true);
  assert.equal(data.deployment_id, deploymentId);
  assert.equal(data.source_sha256, sourceSha256);
  assert.equal(data.config_sha256, configSha256);
  assert.equal(data.provider, "workers-ai");
  assert.equal(data.route_rate_limits_ready, true);
  assert.equal(JSON.stringify(data).includes(TOKEN), false);
});

test("capability truth requires the exact Sarah bearer token", async () => {
  const deploymentId = "d".repeat(40);
  const sourceSha256 = "e".repeat(64);
  const configSha256 = "f".repeat(64);
  const env = {
    AI: { run: async () => ({ response: "unused" }) },
    MODEL_RATE_LIMITER: { limit: async () => ({ success: true }) },
    SEARCH_RATE_LIMITER: { limit: async () => ({ success: true }) },
    VOICE_RATE_LIMITER: { limit: async () => ({ success: true }) },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    SARAH_MODEL_ID: "@cf/google/gemma-4-26b-a4b-it",
    SARAH_DEPLOYMENT_ID: deploymentId,
    SARAH_SOURCE_SHA256: sourceSha256,
    SARAH_CONFIG_SHA256: configSha256,
  };

  const absent = await worker.fetch(
    new Request("https://sarah.example/capabilities"), env);
  assert.equal(absent.status, 401);
  assert.deepEqual(await absent.json(), { error: "unauthorized" });

  const wrong = await worker.fetch(new Request("https://sarah.example/capabilities", {
    headers: { Authorization: "Bearer wrong-token" },
  }), env);
  assert.equal(wrong.status, 401);
  assert.deepEqual(await wrong.json(), { error: "unauthorized" });

  const exact = await worker.fetch(new Request("https://sarah.example/capabilities", {
    headers: { Authorization: `Bearer ${TOKEN}` },
  }), env);
  const data = await exact.json();
  assert.equal(exact.status, 200);
  assert.equal(data.ok, true);
  assert.equal(data.deployment_ready, true);
  assert.equal(data.deployment_id, deploymentId);
  assert.equal(data.source_sha256, sourceSha256);
  assert.equal(data.config_sha256, configSha256);
  assert.equal(data.route_rate_limits_ready, true);
  assert.equal(JSON.stringify(data).includes(TOKEN), false);
});

test("capability route fails closed when the server token is not configured", async () => {
  const response = await worker.fetch(new Request("https://sarah.example/capabilities", {
    headers: { Authorization: `Bearer ${TOKEN}` },
  }), {
    AI: { run: async () => ({ response: "unused" }) },
    SARAH_MODEL_PROVIDER: "workers-ai",
  });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "server_not_configured" });
});

test("route rate limit rejection is explicit and prevents provider work", async () => {
  let providerRuns = 0;
  const env = {
    AI: { run: async () => { providerRuns += 1; return { response: "should not run" }; } },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    MODEL_RATE_LIMITER: { limit: async () => ({ success: false }) },
  };
  const response = await worker.fetch(request({ message: "Hello" }), env);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("Retry-After"), "60");
  assert.deepEqual(await response.json(), { error: "rate_limited", route: "conversation" });
  assert.equal(providerRuns, 0);
});

test("route rate limiter failure fails closed before provider work", async () => {
  let providerRuns = 0;
  const env = {
    AI: { run: async () => { providerRuns += 1; return { response: "should not run" }; } },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    MODEL_RATE_LIMITER: { limit: async () => { throw new Error("limiter unavailable"); } },
  };
  const response = await worker.fetch(request({ message: "Hello" }), env);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "rate_limiter_unavailable", route: "conversation" });
  assert.equal(providerRuns, 0);
});

test("identity-bound candidate fails closed when a route limiter binding is absent", async () => {
  let providerRuns = 0;
  const env = {
    AI: { run: async () => { providerRuns += 1; return { response: "should not run" }; } },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    SARAH_DEPLOYMENT_ID: "d".repeat(40),
    SARAH_SOURCE_SHA256: "e".repeat(64),
    SARAH_CONFIG_SHA256: "f".repeat(64),
  };
  const response = await worker.fetch(request({ message: "Hello" }), env);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "rate_limiter_unavailable", route: "conversation" });
  assert.equal(providerRuns, 0);
});

test("health exposes absent deployment identity to the fail-closed verifier", async () => {
  const env = {
    AI: { run: async () => ({ response: "unused" }) },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
  };
  const response = await worker.fetch(new Request("https://sarah.example/health"), env);
  const data = await response.json();
  assert.equal(response.status, 200);
  assert.equal(data.ok, true);
  assert.equal(data.deployment_ready, false);
  assert.equal(data.contract_version, "sarah-model-proxy-v2-workers-ai-voice");
});

test("health rejects truncated or malformed deployment identity metadata", async () => {
  const env = {
    AI: { run: async () => ({ response: "unused" }) },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    SARAH_DEPLOYMENT_ID: `${"a".repeat(40)}extra`,
    SARAH_SOURCE_SHA256: "b".repeat(64),
    SARAH_CONFIG_SHA256: "c".repeat(64),
  };
  const response = await worker.fetch(new Request("https://sarah.example/health"), env);
  const data = await response.json();
  assert.equal(data.ok, true);
  assert.equal(data.deployment_ready, false);
  assert.equal(data.deployment_id, null);
});

test("conversation route rejects the wrong Sarah token", async () => {
  const env = {
    AI: { run: async () => ({ response: "should not run" }) },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
  };
  const response = await worker.fetch(request({ message: "Hello" }, "wrong"), env);
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});

test("Workers AI receives Sarah's shared contract and returns reply", async () => {
  let invokedModel = "";
  let invokedPayload = null;
  const env = {
    AI: {
      run: async (model, payload) => {
        invokedModel = model;
        invokedPayload = payload;
        return { response: "ONLINE_READY" };
      },
    },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    SARAH_MODEL_ID: "@cf/google/gemma-4-26b-a4b-it",
    SARAH_MAX_OUTPUT_TOKENS: "600",
  };
  const response = await worker.fetch(request({
    provider: "workers-ai",
    model: "@cf/qwen/qwen3-30b-a3b-fp8",
    system_prompt: "Reply naturally.",
    history: [
      { role: "assistant", content: "Earlier reply" },
      { role: "user", content: "Hello Sarah" },
    ],
    message: "Hello Sarah",
    web_search: false,
  }), env);
  const data = await response.json();
  assert.equal(response.status, 200);
  assert.equal(data.reply, "ONLINE_READY");
  assert.equal(data.provider, "workers-ai");
  assert.equal(invokedModel, "@cf/google/gemma-4-26b-a4b-it");
  assert.equal(invokedPayload.max_tokens, 600);
  assert.deepEqual(invokedPayload.messages.at(-1), { role: "user", content: "Hello Sarah" });
  assert.equal(invokedPayload.messages.filter((row) => row.content === "Hello Sarah").length, 1);
});

test("Workers AI accepts Cloudflare's chat-completion response shape", async () => {
  const env = {
    AI: {
      run: async () => ({
        id: "chatcmpl-test",
        choices: [{ message: { role: "assistant", content: "ONLINE_READY" } }],
      }),
    },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    SARAH_MODEL_ID: "@cf/google/gemma-4-26b-a4b-it",
  };
  const response = await worker.fetch(request({ message: "Hello Sarah" }), env);
  const data = await response.json();
  assert.equal(response.status, 200);
  assert.equal(data.reply, "ONLINE_READY");
});

test("a web-search request cannot be misreported as Workers AI research", async () => {
  let systemMessage = "";
  const env = {
    AI: {
      run: async (_model, payload) => {
        systemMessage = payload.messages[0].content;
        return { response: "I need a sourced lookup for current prices." };
      },
    },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
  };
  const response = await worker.fetch(request({
    system_prompt: "Never invent prices.",
    message: "What is the hotel price today?",
    web_search: true,
  }), env);
  const data = await response.json();
  assert.equal(data.web_search_requested, true);
  assert.equal(data.web_search_applied, false);
  assert.deepEqual(data.source_urls, []);
  assert.match(systemMessage, /No live web-search result is attached/);
});

test("Workers AI uses only proxy-owned Tavily evidence when the protected search secret exists", async () => {
  const originalFetch = globalThis.fetch;
  let systemMessage = "";
  let tavilyRequest = null;
  globalThis.fetch = async (url, options) => {
    tavilyRequest = { url, options };
    return new Response(JSON.stringify({
      request_id: "tavily-test-request",
      results: [
        {
          title: "Official current source",
          url: "https://example.test/current",
          content: "A current, source-bound fixture result.",
        },
        {
          title: "Unsafe source",
          url: "http://example.test/not-accepted",
          content: "This must not be included.",
        },
      ],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    const env = {
      AI: {
        run: async (_model, payload) => {
          systemMessage = payload.messages[0].content;
          return { response: "Source-grounded current answer." };
        },
      },
      TAVILY_API_KEY: "server-only-tavily-test-key",
      SARAH_BACKEND_TOKEN: TOKEN,
      SARAH_MODEL_PROVIDER: "workers-ai",
    };
    const response = await worker.fetch(request({
      message: "What is happening nearby this week?",
      search_query: "Current request: What is happening nearby this week? Verified approximate current area: Newark, New Jersey",
      web_search: true,
    }), env);
    const data = await response.json();
    assert.equal(response.status, 200);
    assert.equal(tavilyRequest.url, "https://api.tavily.com/search");
    assert.equal(tavilyRequest.options.headers.Authorization, "Bearer server-only-tavily-test-key");
    const body = JSON.parse(tavilyRequest.options.body);
    assert.equal(body.search_depth, "basic");
    assert.equal(body.max_results, 5);
    assert.match(body.query, /Newark, New Jersey/);
    assert.equal(data.web_search_applied, true);
    assert.equal(data.web_search_provider, "tavily");
    assert.deepEqual(data.source_urls, ["https://example.test/current"]);
    assert.match(systemMessage, /CURRENT SOURCE EVIDENCE/);
    assert.match(systemMessage, /https:\/\/example\.test\/current/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("protected search returns bounded HTTPS results without exposing the provider key", async () => {
  const originalFetch = globalThis.fetch;
  let upstreamBody = null;
  globalThis.fetch = async (_url, options) => {
    upstreamBody = JSON.parse(options.body);
    return new Response(JSON.stringify({
      request_id: "search-route-fixture",
      results: [
        { title: "Official source", url: "https://example.test/nz", content: "Verified fixture." },
        { title: "Rejected source", url: "http://example.test/plain", content: "Not HTTPS." },
      ],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    const env = { SARAH_BACKEND_TOKEN: TOKEN, TAVILY_API_KEY: "server-only-search-key" };
    const response = await worker.fetch(request(
      { query: "New Zealand visitor information", max_results: 2 }, TOKEN, "/search"), env);
    const data = await response.json();
    assert.equal(response.status, 200);
    assert.equal(upstreamBody.max_results, 2);
    assert.equal(data.provider, "tavily");
    assert.equal(data.web_search_applied, true);
    assert.deepEqual(data.results, [{
      title: "Official source",
      url: "https://example.test/nz",
      summary: "Verified fixture.",
    }]);
    assert.equal(JSON.stringify(data).includes("server-only-search-key"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("protected search is authenticated and fails closed without its server secret", async () => {
  const configured = { SARAH_BACKEND_TOKEN: TOKEN, TAVILY_API_KEY: "server-only" };
  const unauthorized = await worker.fetch(request({ query: "nearby" }, "wrong", "/search"), configured);
  assert.equal(unauthorized.status, 401);
  const unavailable = await worker.fetch(request(
    { query: "nearby" }, TOKEN, "/search"), { SARAH_BACKEND_TOKEN: TOKEN });
  assert.equal(unavailable.status, 503);
  assert.deepEqual(await unavailable.json(), { error: "current_source_not_configured" });
});

test("OpenAI web evidence requires a completed tool call and HTTPS source URL", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({
    output: [
      { type: "web_search_call", status: "completed" },
      {
        type: "message",
        content: [{
          type: "output_text",
          text: "Verified current result.",
          annotations: [{ type: "url_citation", url: "https://example.test/source" }],
        }],
      },
    ],
  }), { status: 200, headers: { "Content-Type": "application/json" } });
  try {
    const env = {
      OPENAI_API_KEY: "server-only-test-key",
      SARAH_BACKEND_TOKEN: TOKEN,
      SARAH_MODEL_PROVIDER: "openai",
      SARAH_MODEL_ID: "gpt-test",
    };
    const response = await worker.fetch(request({
      provider: "openai",
      message: "Find a current sourced fact",
      web_search: true,
    }), env);
    const data = await response.json();
    assert.equal(response.status, 200);
    assert.equal(data.web_search_requested, true);
    assert.equal(data.web_search_applied, true);
    assert.deepEqual(data.source_urls, ["https://example.test/source"]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("OpenAI web evidence rejects arbitrary response URLs and incomplete calls", async () => {
  const originalFetch = globalThis.fetch;
  const cases = [
    {
      output: [
        { type: "web_search_call", status: "completed" },
        {
          type: "message",
          metadata: { url: "https://invented.example/not-a-citation" },
          content: [{ type: "output_text", text: "Unsourced response.", annotations: [] }],
        },
      ],
    },
    {
      output: [
        { type: "web_search_call", status: "incomplete" },
        {
          type: "message",
          content: [{
            type: "output_text",
            text: "Unfinished search.",
            annotations: [{ type: "url_citation", url: "https://example.test/source" }],
          }],
        },
      ],
    },
  ];
  try {
    for (const upstream of cases) {
      globalThis.fetch = async () => new Response(JSON.stringify(upstream), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
      const env = {
        OPENAI_API_KEY: "server-only-test-key",
        SARAH_BACKEND_TOKEN: TOKEN,
        SARAH_MODEL_PROVIDER: "openai",
        SARAH_MODEL_ID: "gpt-test",
      };
      const response = await worker.fetch(request({
        provider: "openai",
        message: "Find a current sourced fact",
        web_search: true,
      }), env);
      const data = await response.json();
      assert.equal(data.web_search_applied, false);
      assert.deepEqual(data.source_urls, []);
    }
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("protected voice route keeps the ElevenLabs key server-side", async () => {
  const originalFetch = globalThis.fetch;
  let upstreamUrl = "";
  let upstreamOptions = null;
  globalThis.fetch = async (url, options) => {
    upstreamUrl = String(url);
    upstreamOptions = options;
    return new Response(new Uint8Array([1, 2, 3, 4]), {
      status: 200,
      headers: { "Content-Type": "audio/mpeg" },
    });
  };
  try {
    const env = {
      AI: { run: async () => ({ response: "unused" }) },
      SARAH_BACKEND_TOKEN: TOKEN,
      ELEVENLABS_API_KEY: "server-only-test-key",
      SARAH_ELEVENLABS_VOICE_ID: "approvedVoice123",
      SARAH_ELEVENLABS_MODEL_ID: "eleven_flash_v2_5",
    };
    const voiceRequest = new Request("https://sarah.example/voice", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ text: "Hello Robert", voice_id: "approvedVoice123" }),
    });
    const response = await worker.fetch(voiceRequest, env);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("X-Sarah-Voice-Route"), "elevenlabs-protected");
    assert.match(upstreamUrl, /approvedVoice123/);
    assert.equal(upstreamOptions.headers["xi-api-key"], "server-only-test-key");
    assert.equal(JSON.stringify(upstreamOptions.body).includes("server-only-test-key"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("voice route rejects a voice substitution", async () => {
  const env = {
    AI: { run: async () => ({ response: "unused" }) },
    SARAH_BACKEND_TOKEN: TOKEN,
    ELEVENLABS_API_KEY: "server-only-test-key",
    SARAH_ELEVENLABS_VOICE_ID: "approvedVoice123",
  };
  const voiceRequest = new Request("https://sarah.example/voice", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ text: "Hello", voice_id: "differentVoice" }),
  });
  const response = await worker.fetch(voiceRequest, env);
  assert.equal(response.status, 403);
  assert.deepEqual(await response.json(), { error: "voice_not_approved" });
});
