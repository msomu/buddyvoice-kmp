# BuddyVoice server proxy

A tiny Cloudflare Worker with one job: hold your real provider API keys as
Worker Secrets and hand out **short-lived ephemeral tokens** to clients that
present the shared proxy key. Clients then talk to the provider directly —
no audio flows through the Worker, and no long-lived key ever reaches a device.

## Deploy your own in 5 minutes

Prerequisites: a free [Cloudflare account](https://dash.cloudflare.com/sign-up)
and an [xAI API key](https://console.x.ai/).

```bash
cd server-proxy
npm install

# 1. Authenticate wrangler with your Cloudflare account
npx wrangler login

# 2. Set the secrets (you'll be prompted for the values — they are never stored in the repo)
npx wrangler secret put XAI_API_KEY            # your xAI key
npx wrangler secret put BUDDYVOICE_PROXY_KEY   # invent a long random string, e.g. `openssl rand -hex 32`

# 3. Ship it
npx wrangler deploy
```

The deploy prints your Worker URL, e.g. `https://buddyvoice-proxy.<you>.workers.dev`.

### Point the sample app at it

Add to `local.properties` at the repo root (gitignored — never committed):

```properties
buddyvoice.proxyBaseUrl=https://buddyvoice-proxy.YOUR_SUBDOMAIN.workers.dev
buddyvoice.proxyKey=THE_SAME_LONG_RANDOM_STRING
```

### Test it

```bash
curl -X POST \
  -H "X-BuddyVoice-Proxy-Key: THE_SAME_LONG_RANDOM_STRING" \
  https://buddyvoice-proxy.YOUR_SUBDOMAIN.workers.dev/session/grok
```

You should get JSON containing a short-lived token. Without the header you get a 401.

## Local development

```bash
cp .dev.vars.example .dev.vars   # fill in real values; .dev.vars is gitignored
npx wrangler dev                 # serves http://localhost:8787
```

## Dev/production environments

`wrangler.toml` defines a `dev` environment so contributors can run a personal
worker without touching a production one:

```bash
npx wrangler secret put XAI_API_KEY --env dev
npx wrangler secret put BUDDYVOICE_PROXY_KEY --env dev
npx wrangler deploy --env dev    # deploys buddyvoice-proxy-dev
```

## Security notes

- Secrets exist **only** as Worker Secrets (`wrangler secret put`). They are never
  in `wrangler.toml`, never in code, never in CI logs.
- Every route validates `X-BuddyVoice-Proxy-Key` (constant-time compare) before
  touching a provider API.
- The minted tokens expire after ~5 minutes; leaking one is a bounded problem.
- The shared key is a gate against casual scraping of a public Worker URL, not
  user authentication. Add real auth before shipping this to production users.
