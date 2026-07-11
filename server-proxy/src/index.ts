/**
 * BuddyVoice token-mint proxy.
 *
 * One job: validate the client's shared secret, then mint a short-lived
 * ephemeral token from the provider so the client can connect directly.
 * The long-lived provider key exists only as a Worker Secret — it never
 * reaches a client, a log line, or this repository.
 */

export interface Env {
  /** xAI API key (Worker Secret). */
  XAI_API_KEY: string;
  /** OpenAI API key (Worker Secret) — only needed if /session/openai is used. */
  OPENAI_API_KEY?: string;
  /** ElevenLabs API key (Worker Secret) — only needed if /session/elevenlabs is used. */
  ELEVENLABS_API_KEY?: string;
  /** Default ElevenLabs agent id (Worker Secret) — clients may override per request. */
  ELEVENLABS_AGENT_ID?: string;
  /** Shared secret clients must send as X-BuddyVoice-Proxy-Key (Worker Secret). */
  BUDDYVOICE_PROXY_KEY: string;
}

const XAI_CLIENT_SECRETS_URL = "https://api.x.ai/v1/realtime/client_secrets";
const OPENAI_CLIENT_SECRETS_URL = "https://api.openai.com/v1/realtime/client_secrets";
const OPENAI_DEFAULT_MODEL = "gpt-realtime";
const ELEVENLABS_SIGNED_URL = "https://api.elevenlabs.io/v1/convai/conversation/get-signed-url";
const TOKEN_TTL_SECONDS = 300;

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, X-BuddyVoice-Proxy-Key",
};

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

/** Constant-time comparison; hashing first gives timingSafeEqual equal-length inputs. */
async function keyMatches(provided: string, expected: string): Promise<boolean> {
  const encoder = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(provided)),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

async function mintToken(url: string, apiKey: string, body: unknown): Promise<Response> {
  const upstream = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  if (!upstream.ok) {
    // Deliberately not forwarding the upstream body: it could reference key details.
    return json({ error: `provider returned ${upstream.status}` }, 502);
  }

  // Pass the body through untouched; the client parses the token defensively.
  return new Response(await upstream.text(), {
    status: 200,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

function mintGrokToken(env: Env): Promise<Response> {
  return mintToken(XAI_CLIENT_SECRETS_URL, env.XAI_API_KEY, {
    expires_after: { seconds: TOKEN_TTL_SECONDS },
  });
}

function mintOpenAIToken(env: Env): Promise<Response> {
  if (!env.OPENAI_API_KEY) {
    // Secret not set on this worker; see server-proxy/README.md.
    return Promise.resolve(json({ error: "openai is not configured on this proxy" }, 501));
  }
  return mintToken(OPENAI_CLIENT_SECRETS_URL, env.OPENAI_API_KEY, {
    expires_after: { anchor: "created_at", seconds: TOKEN_TTL_SECONDS },
    session: { type: "realtime", model: OPENAI_DEFAULT_MODEL },
  });
}

/**
 * ElevenLabs differs from the token-mint providers: the short-lived credential
 * is a signed WebSocket URL fetched with the account's xi-api-key. The agent id
 * comes from the request body (optional) or the worker's default.
 */
async function mintElevenLabsUrl(env: Env, request: Request): Promise<Response> {
  if (!env.ELEVENLABS_API_KEY) {
    // Secret not set on this worker; see server-proxy/README.md.
    return json({ error: "elevenlabs is not configured on this proxy" }, 501);
  }
  let agentId = env.ELEVENLABS_AGENT_ID;
  try {
    const body = (await request.json()) as { agentId?: string };
    if (typeof body.agentId === "string" && body.agentId.length > 0) {
      agentId = body.agentId;
    }
  } catch {
    // Empty or non-JSON body: fall through to the configured default.
  }
  if (!agentId) {
    return json({ error: "no elevenlabs agent id configured or provided" }, 400);
  }

  const upstream = await fetch(
    `${ELEVENLABS_SIGNED_URL}?agent_id=${encodeURIComponent(agentId)}`,
    { headers: { "xi-api-key": env.ELEVENLABS_API_KEY } },
  );
  if (!upstream.ok) {
    // Deliberately not forwarding the upstream body: it could reference key details.
    return json({ error: `provider returned ${upstream.status}` }, 502);
  }

  // Pass the body through untouched ({"signed_url": ...}).
  return new Response(await upstream.text(), {
    status: 200,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const mint =
      url.pathname === "/session/grok" ? () => mintGrokToken(env)
      : url.pathname === "/session/openai" ? () => mintOpenAIToken(env)
      : url.pathname === "/session/elevenlabs" ? () => mintElevenLabsUrl(env, request)
      : null;
    if (request.method !== "POST" || mint === null) {
      return json({ error: "not found" }, 404);
    }

    const providedKey = request.headers.get("X-BuddyVoice-Proxy-Key");
    if (!providedKey || !(await keyMatches(providedKey, env.BUDDYVOICE_PROXY_KEY))) {
      return json({ error: "unauthorized" }, 401);
    }

    return mint();
  },
} satisfies ExportedHandler<Env>;
