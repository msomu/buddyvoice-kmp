// Copy this file to local.config.js (gitignored) and fill in your own proxy
// details — see server-proxy/README.md for deploying the token-mint Worker.
//
// SECURITY: local.config.js must never be committed, and it only ever holds
// your proxy's URL and its shared-secret gate. No provider API key belongs
// here or anywhere else in client code.
window.BUDDYVOICE_CONFIG = {
  proxyBaseUrl: "https://your-worker.your-subdomain.workers.dev",
  proxyKey: "YOUR_PROXY_SHARED_SECRET",
};
