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
  /** Shared secret clients must send as X-BuddyVoice-Proxy-Key (Worker Secret). */
  BUDDYVOICE_PROXY_KEY: string;
}

const XAI_CLIENT_SECRETS_URL = "https://api.x.ai/v1/realtime/client_secrets";
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

async function mintGrokToken(env: Env): Promise<Response> {
  const upstream = await fetch(XAI_CLIENT_SECRETS_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.XAI_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ expires_after: { seconds: TOKEN_TTL_SECONDS } }),
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

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/session/grok") {
      return json({ error: "not found" }, 404);
    }

    const providedKey = request.headers.get("X-BuddyVoice-Proxy-Key");
    if (!providedKey || !(await keyMatches(providedKey, env.BUDDYVOICE_PROXY_KEY))) {
      return json({ error: "unauthorized" }, 401);
    }

    return mintGrokToken(env);
  },
} satisfies ExportedHandler<Env>;
