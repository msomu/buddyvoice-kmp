# Releasing buddyvoice-* to Maven Central

The publish pipeline is fully automated by
[`.github/workflows/publish.yml`](.github/workflows/publish.yml) — pushing a
`vX.Y.Z` tag publishes every library module (`buddyvoice-core`, `-audio`,
`-transport`, `-provider-grok`, `-provider-openai`, `-provider-elevenlabs`)
under the `io.github.msomu` group. What follows is the one-time credential
setup only the repo owner can do, then the two-minute per-release routine.

## One-time setup

### 1. Central Portal account + namespace

1. Sign in at [central.sonatype.com](https://central.sonatype.com) **with GitHub**.
   Signing in with the GitHub account `msomu` automatically registers and
   verifies the `io.github.msomu` namespace — no DNS or ticket dance.
2. Generate a **user token**: Account → Generate User Token. Note the
   username + password pair it gives you.

### 2. GPG signing key

Maven Central requires signed artifacts. On any machine with `gpg`:

```bash
gpg --gen-key                        # name + email, no comment; pick a passphrase
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # publish the public key
gpg --export-secret-keys --armor <KEY_ID>                   # prints the private key block
```

### 3. Repo secrets

Add four secrets under GitHub → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |
| `SIGNING_IN_MEMORY_KEY` | the full armored private key block (including header/footer lines) |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | the key's passphrase |

Signing is skipped automatically when no key is configured, so local builds
and `publishToMavenLocal` keep working without any of this.

## Every release

```bash
# 1. Set the version (single source of truth for all modules)
sed -i '' 's/^VERSION_NAME=.*/VERSION_NAME=0.1.0/' gradle.properties
git commit -am "release: 0.1.0"

# 2. Tag and push — the tag triggers the publish workflow
git tag v0.1.0
git push origin main v0.1.0

# 3. Back to snapshots
sed -i '' 's/^VERSION_NAME=.*/VERSION_NAME=0.2.0-SNAPSHOT/' gradle.properties
git commit -am "chore: back to snapshots" && git push
```

The workflow refuses to run if the tag doesn't match `VERSION_NAME` or if the
version is a `-SNAPSHOT`. Artifacts land on Maven Central after Central Portal
validation (usually 10–30 minutes). Versioning follows semver per the PRD:
bump minor for each phase that adds a platform or provider.
