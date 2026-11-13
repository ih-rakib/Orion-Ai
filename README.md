# Orion

An Android chatbot that chains free AI models in order, so a task keeps
working when one model hits its limit: if one ends, another starts.

Chat like in any chatbot app — message bubbles, a chats list, follow-ups,
Continue, and Regenerate — while Orion tries each provider in turn behind
the scenes and shows which model answered. Keys are pasted inside the app
only, encrypted with the Android Keystore, and never baked into the app.

Package: `com.rakib.orionrouter` (the package name is kept from v1.0 on
purpose: renaming it would make upgrades forget saved keys). This is a
different app from the [Orion universe explorer](https://orion-universe.vercel.app/)
and from [Orion-IDM](https://github.com/ih-rakib/Orion-IDM).

## The chain

Every provider speaks OpenAI-compatible `/chat/completions`, so the
router just tries each endpoint in order. One attempt per model, plus a
single retry when a 429 carries a short Retry-After (15s or less).
429 / 5xx / timeout / 404 (dead model id) / 401 (bad key) / 400 / empty
reply / network error all fall through to the next model. The attempt
log (Settings) shows the HTTP code and the provider's own error message,
and never contains a key.

**There are no fixed provider slots.** The provider list lives in
Settings: add a provider whenever you get a new key, rename it, move it
up or down to change the chain order, or delete it. Any OpenAI-compatible
gateway works — paste its base URL (ending in `/v1`), key, and model
names. The table below is only the starting set on a fresh install;
upgrades from v1.0–v2.1 keep their saved providers, keys, and any
customised models or base URLs (same ids, converted automatically).

| Starting provider | Default models | Notes |
|---|---|---|
| Groq | `openai/gpt-oss-120b`, `openai/gpt-oss-20b` | First: fastest, biggest free cap |
| Google Gemini | `gemini-2.5-flash`, `gemini-2.5-flash-lite` | Via the OpenAI-compatible endpoint |
| OpenRouter | `nvidia/nemotron-3-ultra-550b-a55b:free`, `openrouter/free`, `google/gemma-4-31b-it:free` | Free models only; Settings lists the current `:free` ids live |
| SambaNova | `gpt-oss-120b`, `Meta-Llama-3.3-70B-Instruct` | Off by default |
| Cloudflare Workers AI | `@cf/openai/gpt-oss-120b` | Off by default; paste your Account ID into the base URL |
| AgentRouter | `deepseek-v4-flash`, `glm-5.3`, `gpt-5.6-sol` | Free signup credits; premium models burn credits, daily batches, models come and go — see below |
| Custom / Gwarden | `deepseek-v4.1-flash-1017` | Off by default; last resort, can be very slow |

Free-tier limits change often — use "Test all providers" in Settings
and the live OpenRouter list rather than trusting any fixed numbers.

AgentRouter notes (checked Nov 2026): it is a community-run,
credit-based relay, not a stable first-party API. Its model list
changes with available resources (other current ids include
`claude-opus-5` and `claude-opus-4-8` — paste them into the models box
if you want them), Claude/GPT models are released in daily batches
and fail with 402 when a batch is used up, and upstream model
authenticity should be self-verified. It sits behind the truly-free
providers so free quotas are used before credits burn. Treat it like
the other gateways: throwaway/free experimentation only — do not pay
and do not send sensitive material.

## Features

- Chat bubbles with the answering provider/model on each answer
- Multiple chats (Chats list, tap to open, long-press to delete);
  v1.1's single conversation is imported as one chat on first launch
- Multi-turn follow-ups; sent history capped at ~12,000 chars with
  oldest turns dropped from the sent copy (stored history untouched)
- System prompt, creativity (temperature 0–2), max output tokens
- Continue last answer (merges into the previous answer), Regenerate
  (the old answer is restored if the new try fails), Stop (disconnects
  the in-flight request)
- Long-press any answer to copy it
- Per-provider daily counters (ok / limited / failed) in Settings
- "Test all providers" health check and live OpenRouter free-model list

## Build (no Gradle)

Same pattern as Orion-IDM: `aapt2` + `javac` + `d8` + `zipalign` +
`apksigner`, with JDK 17 and Android SDK platform 34 / build-tools 34.

```sh
./build.sh   # writes build/Orion.apk, signed with debug.keystore
```

`build.sh` wipes `build/res-compiled`, `build/gen`, and `build/classes`
at the start of every build: `aapt2 link` globs `*.flat`, so a deleted
resource file would otherwise survive into the next APK from stale
flats. The debug keystore is local-only (not in this repo); the APK
that ships must be signed with the same certificate as the installed
one, or Android refuses the upgrade and saved keys are lost.

Requirements: `ANDROID_HOME` (or `~/android-sdk`), JDK 17
(`~/jdk17` if present). Min API 26, target 34. Permissions: INTERNET
and ACCESS_NETWORK_STATE only. `allowBackup` is false.

## Icon

The launcher icon is a vector constellation (gold stars + lines on
navy) in `res/drawable/ic_launcher_foreground.xml`, so this repo
builds standalone with no binary assets. A photo-real PNG master of
the same constellation lives only on the author's machine
(`icon-src/`, `Orion-icon.png`) and is not in this repo.

## Privacy

- Keys are encrypted with Android Keystore AES/GCM before storage.
  If the Keystore cannot read them back (device security changed),
  the stored keys are cleared and the user is told to re-enter them.
- Keys are sent only as Bearer tokens over HTTPS to their own
  provider. The app has no account, no analytics, no server.
- Free-tier prompts may be used for training by some providers.
  Avoid passwords, private factory data, or anything sensitive.

## History

- **v2.2** — No fixed slots: providers are a user-managed list
  (add / rename / reorder / delete in Settings), with automatic
  migration of saved v1.0–v2.1 providers and keys.
- **v2.1** — AgentRouter added as provider 6 (DeepSeek V4 Flash,
  GLM-5.3, GPT-5.6 Sol), behind the free providers so credits burn last.
- **v2.0** — Chatbot interface: bubbles, chats list, Settings screen,
  Stop / Regenerate, attempt log shared with Settings. Same chain.
- **v1.1** — Renamed to Orion, constellation icon, multi-turn history,
  system prompt, temperature, Continue, Retry-After retry, progress.
- **v1.0** — First build: single-prompt sends over the 6-provider chain.

## Status

Built and verified by build tooling only (compiles, signs, badging,
permissions, no-secrets check). On-phone testing is done by the
author; no live API call has been made from this code by anyone but
him, because the keys live only with him.
