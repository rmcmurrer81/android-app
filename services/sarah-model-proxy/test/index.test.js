import assert from "node:assert/strict";
import test from "node:test";

import worker from "../src/index.js";

const TOKEN = "test-only-sarah-token";

function request(body, token = TOKEN) {
  return new Request("https://sarah.example/", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

test("Workers AI health reports the configured provider without exposing secrets", async () => {
  const env = {
    AI: { run: async () => ({ response: "unused" }) },
    SARAH_BACKEND_TOKEN: TOKEN,
    SARAH_MODEL_PROVIDER: "workers-ai",
    SARAH_MODEL_ID: "@cf/google/gemma-4-26b-a4b-it",
  };
  const response = await worker.fetch(new Request("https://sarah.example/health"), env);
  const data = await response.json();
  assert.equal(response.status, 200);
  assert.equal(data.ok, true);
  assert.equal(data.provider, "workers-ai");
  assert.equal(JSON.stringify(data).includes(TOKEN), false);
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
  assert.equal(data.web_search_applied, false);
  assert.match(systemMessage, /No live web-search result is attached/);
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
