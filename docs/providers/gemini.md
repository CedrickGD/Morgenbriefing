# Provider spec: Google Gemini API (Google AI Studio keys)

**AS_OF: 2026-10-09.** Every fact below comes from Google's official Gemini API docs and API
reference, fetched on 2026-10-09 (raw HTML via `curl`, page footers give "Last updated").
A few facts come from **live probes** made on 2026-10-09 with obviously fake keys; they are marked
**OBSERVED**. Anything not covered by the docs is marked **UNVERIFIED** or listed under
*Open questions*. Nothing here comes from memory.

Target: Morgenbriefing, Android (Kotlin, OkHttp 5, kotlinx-serialization, minSdk 24), plain HTTPS +
SSE, no vendor SDK. Host: `generativelanguage.googleapis.com`, auth header `x-goog-api-key`.

> **Big change since 2025:** Google now has **two** REST surfaces for the same models.
> * `models/{model}:generateContent` / `:streamGenerateContent?alt=sse`: called "legacy" but
>   "remains fully supported" [G25, G28]. The docs for it moved under `/gemini-api/docs/generate-content/...`.
> * **Interactions API** `POST /v1beta/interactions` with `stream: true`: "Generally Available
>   and recommended for all new projects" since June 2026. The default docs pages
>   (`/gemini-api/docs/google-search`, `/function-calling`, `/streaming`, ...) now show **only** this API [G25].
>
> This spec documents **generateContent as the primary path** (as requested, stable proto schema,
> `timeRangeFilter` for news) and the **Interactions API as a full alternative** in §10.

---

## 0. Sources (all fetched 2026-10-09; "LU" = page footer "Last updated")

| # | Page | URL | LU |
|---|---|---|---|
| G1 | Models overview | https://ai.google.dev/gemini-api/docs/models | 2026-10-06 |
| G2 | Model pages | https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash (also `/gemini-3.7-flash`, `/gemini-3.6-flash`, `/gemini-3.5-flash`, `/gemini-3.5-flash-lite`, `/gemini-3.1-flash-lite`, `/gemini-3.1-pro-preview`, `/gemini-3-flash-preview`) | 2026-07-21 … 2026-09-02 |
| G3 | Pricing | https://ai.google.dev/gemini-api/docs/pricing | 2026-10-07 |
| G4 | What's new in Gemini 3.8 Flash ("latest model") | https://ai.google.dev/gemini-api/docs/latest-model | 2026-10-07 |
| G5 | What's new 3.7 / 3.6 + 3.5 Flash-Lite / 3.5 Flash | https://ai.google.dev/gemini-api/docs/whats-new-gemini-3.7 , …/whats-new-gemini-3.6 , …/whats-new-gemini-3.5 | 2026-10-07 / 2026-10-07 / 2026-10-09 |
| G6 | generateContent API reference (request, response, Part, Tool, GoogleSearch, GroundingMetadata, GenerationConfig, Schema, FinishReason, UsageMetadata) | https://ai.google.dev/api/generate-content | 2026-09-23 |
| G7 | ToolConfig / FunctionCallingConfig reference | https://ai.google.dev/api/caching#ToolConfig | 2026-09-11 |
| G8 | Models API reference (models.get / models.list) | https://ai.google.dev/api/models | 2026-09-23 |
| G9 | Text generation (generateContent) | https://ai.google.dev/gemini-api/docs/generate-content/text-generation | 2026-09-17 |
| G10 | Grounding with Google Search (generateContent) | https://ai.google.dev/gemini-api/docs/generate-content/google-search | 2026-09-02 |
| G11 | Grounding with Google Search (Interactions) | https://ai.google.dev/gemini-api/docs/google-search | 2026-09-23 |
| G12 | Function calling (generateContent) | https://ai.google.dev/gemini-api/docs/generate-content/function-calling | 2026-09-16 |
| G13 | Combine built-in tools and function calling (generateContent) | https://ai.google.dev/gemini-api/docs/generate-content/tool-combination | 2026-10-05 |
| G14 | Combine built-in tools and function calling (Interactions) | https://ai.google.dev/gemini-api/docs/tool-combination | 2026-09-23 |
| G15 | Structured outputs (generateContent / Interactions) | https://ai.google.dev/gemini-api/docs/generate-content/structured-output , https://ai.google.dev/gemini-api/docs/structured-output | 2026-09-02 / 2026-09-23 |
| G16 | Thought signatures (generateContent) | https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures | 2026-09-04 |
| G17 | Gemini 3 developer guide (page marked deprecated) | https://ai.google.dev/gemini-api/docs/generate-content/gemini-3 | 2026-09-03 |
| G18 | API errors (generateContent, gRPC format) | https://ai.google.dev/gemini-api/docs/generate-content/api-errors | 2026-09-20 |
| G19 | API errors (Interactions, snake_case codes) | https://ai.google.dev/gemini-api/docs/api-errors | 2026-09-20 |
| G20 | Troubleshooting (retry strategy) | https://ai.google.dev/gemini-api/docs/troubleshooting | 2026-10-01 |
| G21 | Rate limits | https://ai.google.dev/gemini-api/docs/rate-limits | 2026-09-02 |
| G22 | Using Gemini API keys | https://ai.google.dev/gemini-api/docs/api-key | 2026-10-06 |
| G23 | Billing (Prepay/Postpay, free tier) | https://ai.google.dev/gemini-api/docs/billing | 2026-09-28 |
| G24 | Gemini API Additional Terms of Service (section "Grounding with Google Search") | https://ai.google.dev/gemini-api/terms | 2026-04-28 |
| G25 | Interactions API overview | https://ai.google.dev/gemini-api/docs/interactions-overview | 2026-10-01 |
| G26 | Interactions API reference (CreateInteraction, SSE events, Steps, Usage) | https://ai.google.dev/api/interactions-api | 2026-10-06 |
| G27 | Streaming interactions | https://ai.google.dev/gemini-api/docs/streaming | 2026-09-23 |
| G28 | Migrating to the Interactions API | https://ai.google.dev/gemini-api/docs/migrate-to-interactions | 2026-09-23 |
| G29 | Interactions breaking changes (May 2026) | https://ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026 | 2026-09-04 |
| G30 | Deprecations | https://ai.google.dev/gemini-api/docs/deprecations | 2026-10-07 |
| G31 | Release notes | https://ai.google.dev/gemini-api/docs/changelog | 2026-10-07 |
| G32 | Context caching (generateContent; implicit caching) | https://ai.google.dev/gemini-api/docs/generate-content/caching | 2026-09-11 |
| G33 | Tokens (generateContent / Interactions) | https://ai.google.dev/gemini-api/docs/generate-content/tokens , https://ai.google.dev/gemini-api/docs/tokens | 2026-09-04 / 2026-09-23 |
| G34 | OpenAI compatibility | https://ai.google.dev/gemini-api/docs/openai | 2026-09-02 |
| G35 | **Vertex AI** doc: Google Search suggestions display requirements (same Search-grounding product, Google Cloud docs; supplementary) | https://docs.cloud.google.com/vertex-ai/generative-ai/docs/grounding/grounding-search-suggestions | 2026-10-07 |
| G36 | countTokens API reference | https://ai.google.dev/api/tokens | 2026-08-17 |
| G37 | Available regions | https://ai.google.dev/gemini-api/docs/available-regions | 2026-04-28 |
| P1 | **OBSERVED** live probes with fake keys (no real credentials), 2026-10-09 05:53 UTC | see §12.1 | – |
| F1 | Forum (unofficial, user posts): AQ.-prefix keys and 401s | https://discuss.ai.google.dev/t/aq-prefix-key-returns-401-unauthenticated-via-key-on-generatecontent-key-confirmed-valid-in-ai-studio/178923 , https://discuss.ai.google.dev/t/ai-studio-generates-aq-key-instead-of-aizasy-key-google-genai-sdk-returns-access-token-type-unsupported/187266 | posts 2026-08-19 … 2026-10-08 |

**Re-check 2026-10-09 (evening), for the "nothing works with my new `AQ.` key" report (v1.3.1):** G22
(api-key, LU 2026-10-06), G20 (troubleshooting, LU 2026-10-01), G18 (generateContent API errors, LU
2026-09-20), G23 (billing, LU 2026-09-28), G8 (models API, LU 2026-09-23) and G37 were fetched again;
F1 was read again. What is new or newly relevant is in §12.4–§12.6 and §13; nothing in the official
docs says that auth keys need another header or endpoint.

---

## 1. TL;DR for the implementation

* **Endpoint:** `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse`,
  header `x-goog-api-key: <key>` [G6, G9]. Each SSE `data:` payload is one complete
  `GenerateContentResponse` JSON (text deltas are just new `parts[].text`) [G6].
* **Web search** = built-in tool `{"googleSearch": {}}` (snake_case `{"google_search": {}}` also appears in the docs).
  It runs server-side; sources come back in `candidates[0].groundingMetadata`
  (`groundingChunks[].web.{uri,title}`, `groundingSupports[]`, `webSearchQueries[]`,
  `searchEntryPoint.renderedContent`) [G6, G10]. Optional `timeRangeFilter` {startTime,endTime} (API reference only) [G6].
* **Search + function calling in ONE request works on Gemini 3 models (Preview)**: add
  `"toolConfig": {"includeServerSideToolInvocations": true}` and use function-calling mode
  `VALIDATED` (AUTO is not supported in this mode) [G13, G12]. The response then contains
  `toolCall`/`toolResponse` parts (the search) and a `functionCall` part (`publish_articles`) [G13].
  Documented fallback: two-step (grounded search → forced function call), see §8.3.
* **Structured JSON output + Google Search in one request** (no function) is also Preview, generateContent docs list it **only for
  `gemini-3.1-pro-preview` and `gemini-3.8-flash`** [G15].
* **Models (§3):** default **`gemini-3.8-flash`** ($0.75 in / $3.75 out per 1M through 2026-12-31, then $1.50 / $7.50);
  cheap **`gemini-3.5-flash-lite`** ($0.30 / $2.50); top/Pro **`gemini-3.1-pro-preview`** ($2 / $12, ≤ 200k prompt) [G3, G4].
  **Search grounding (Gemini 3.x): 5,000 free search queries per month shared across all 3.x models, then $14 per 1,000** [G3].
* **Free tier keys cannot use Search grounding on Gemini 3.x** ("Not available", "Can be tested in Google AI Studio") [G3]
  → both features need a **Paid-tier project (Prepay, min. $5)** [G23] when web search is on.
* **Usage:** `usageMetadata` has prompt/cached/candidates/thoughts/toolUse/total token counts, **no cost and no search count** [G6];
  count distinct non-empty `webSearchQueries` yourself (Gemini 3 bills per executed query [G10]).
* **Invalid key = HTTP 400 `INVALID_ARGUMENT` / reason `API_KEY_INVALID`** for classic keys [G18, P1], but
  **401 `UNAUTHENTICATED` / `ACCESS_TOKEN_TYPE_UNSUPPORTED`** for an `AQ.`-style key [P1] — for a mistyped one *and*, per
  user reports, for new auth keys that AI Studio shows as valid [F1]. The app explains the second case (§12.4), maps
  blocked/disabled APIs, the free-tier region and leaked keys on their own, falls back to another model when the chosen one
  is not open to the key (§12.5) and shows a key-free diagnostic line under "Details" (§12.6).
* **Gemini 3 rules:** don't send `temperature`/`topP`/`topK` (deprecated, ignored, future 400), no `candidateCount`,
  **no prefilled model turn** (400), `thinkingLevel` instead of `thinkingBudget` (never both → 400),
  **thought signatures must be echoed** in function-calling turns (else 400) [G5, G16, G17].
* **Terms:** Grounded results must be shown **together with the Search Suggestions** (`searchEntryPoint.renderedContent`, HTML/CSS → WebView),
  unmodified; strict limits on storing/caching grounded results [G24]. See §11.

---

## 2. Endpoint and auth

| Item | Value | Source |
|---|---|---|
| Base | `https://generativelanguage.googleapis.com/v1beta` | G6 |
| Streaming | `POST /v1beta/models/{model}:streamGenerateContent?alt=sse` (path param format `models/{model}`) | G6, G9 |
| Non-streaming | `POST /v1beta/models/{model}:generateContent` | G6 |
| Auth | Header **`x-goog-api-key: <key>`** (all current guide examples). The API reference curl samples still show `?key=` in the URL; don't use that (key ends up in logs/URLs) | G9, G6 |
| Content type | `Content-Type: application/json` | G9 |
| JSON names | Requests accept camelCase **and** snake_case (docs mix `systemInstruction`/`system_instruction`, `googleSearch`/`google_search`, `inlineData`/`inline_data`). Responses use camelCase. Pick camelCase consistently | G9, G10, G13 |
| No key | 403 `PERMISSION_DENIED` "Method doesn't allow unregistered callers" | P1 |
| Model list / info | `GET /v1beta/models`, `GET /v1beta/models/{model}` | G8 |
| Count tokens | `POST /v1beta/models/{model}:countTokens` | G36 |

Key security note from Google: "Do not hardcode API keys directly in web or mobile apps" (they mean *your* key; here the
user brings their own key and it stays in the Keystore) [G22].

---

## 3. Models and prices (AS_OF 2026-10-09)

Paid tier, **Standard** service tier, USD per 1M tokens [G3]. "Output" includes thinking tokens [G3].
Context/output limits from the model pages [G2]. Thinking defaults from G4/G5.

### 3.1 Recommended

| Role | Model ID | In / Out limit | Input | Output | Cached input (+ storage /1M/h) | Thinking | Notes |
|---|---|---|---|---|---|---|---|
| **Default** | `gemini-3.8-flash` | 1,048,576 / 65,536 | **$0.75** through 2026-12-31, **$1.50** from 2027-01-01 | **$3.75** → **$7.50** | $0.075 → $0.15 (+$0.50 → $1.00) | `low`, `medium` (default), `high`. **`minimal` returns an error** | GA 2026-09-02, stable, no shutdown date. Search, function calling, structured outputs, URL context: supported. Structured output **with tools**: supported (Preview) [G2, G3, G4, G15, G30] |
| **Cheap / fast** | `gemini-3.5-flash-lite` | 1,048,576 / 65,536 | $0.30 | $2.50 | $0.03 (+$1.00) | default **`minimal`**; set `medium`/`high` for tool-using subagents "to prevent premature tool termination" | GA 2026-07-21, no shutdown date. Free tier: no caching [G2, G3, G5, G30] |
| **Top (Pro)** | `gemini-3.1-pro-preview` | 1,048,576 / 65,536 | $2.00 (≤ 200k prompt) / $4.00 (> 200k) | $12.00 / $18.00 | $0.20 / $0.40 (+$4.50) | `low`, `medium`, `high` (default). **`minimal` not supported** | **Preview**, released 2026-02-19, no shutdown date; **no free tier**. Variant `gemini-3.1-pro-preview-customtools` (better at prioritizing custom tools) [G2, G3, G5, G30] |

Knowledge cutoff: Gemini 3.5 Flash "January 2025" [G5]; 3.1 Pro Preview / 3.1 Flash-Lite / 3 Flash Preview "Jan 2025" [G17].
Not stated for 3.8 Flash on the fetched pages.

### 3.2 Alternatives [G2, G3, G4, G5, G30]

| Model ID | Input | Output | Cached | Thinking default | Notes |
|---|---|---|---|---|---|
| `gemini-3.7-flash` | $0.75 → $1.50 (2027) | $3.75 → $7.50 | $0.075 → $0.15 | medium; `minimal` → error | GA 2026-08-13 |
| `gemini-3.6-flash` | $0.75 → $1.50 (intro price also applied to 3.6 [G5]) | $3.75 → $7.50 | $0.075 → $0.15 | medium | GA 2026-07-21 |
| `gemini-3.5-flash` | $1.50 | $9.00 | $0.15 | medium | GA 2026-05-19; more expensive than 3.6–3.8 |
| `gemini-3.1-flash-lite` | $0.25 (audio $0.50) | $1.50 | $0.025 | minimal | **Earliest shutdown 2027-05-07** → replacement `gemini-3.5-flash-lite` [G30] |
| `gemini-3-flash-preview` | $0.50 | $3.00 | $0.05 | high | Preview, "legacy"; recommended replacement `gemini-3.6-flash` [G3, G30] |
| `gemini-2.5-flash` / `-flash-lite` / `-pro` | 0.30 / 0.10 / 1.25 | 2.50 / 0.40 / 10.00 | – | thinking budget | **Access limited to users who used them before** (2026-09-18); "For any new projects, use … 3.5 Flash-Lite or 3.8 Flash" [G30, G31]. Search billed per *prompt*: 1,500 RPD free then $35/1,000 [G3]. Don't offer for new keys |

Aliases `gemini-flash-latest`, `gemini-flash-lite-latest`, `gemini-pro-latest` appear in the Interactions model enum [G26];
their current targets are **not documented** on the fetched pages → pin explicit IDs.

Other price tiers: Batch/Flex ≈ 50 % of Standard, Priority ≈ 1.8× (e.g. 3.8 Flash Priority $1.35 / $6.75) [G3]. Not useful for streaming chat.

### 3.3 Grounding with Google Search prices [G3, G10]

| Models | Free tier | Paid tier |
|---|---|---|
| All Gemini 3.x (3.8/3.7/3.6/3.5 Flash, 3.5/3.1 Flash-Lite, 3.1 Pro Preview, 3 Flash Preview) | **Not available** ("Can be tested in Google AI Studio") | **5,000 free search queries per month, shared across all Gemini 3.x models, then $14 per 1,000** |
| Gemini 2.5 Flash / Flash-Lite | 500 RPD free | 1,500 RPD free, then **$35 per 1,000 grounded prompts** |

* Gemini 3 billing unit: "your project is billed for each search query that the model decides to execute"; two queries in one
  request = two billable uses; "we ignore the empty web search queries when counting unique queries" [G10, G11].
  Pricing footnote: "You will be charged for each individual search query performed" [G3].
  (The pricing row itself says "per 1,000 requests" – budget per **query**.)
* Search-result tokens: with tool combination, "Google Search already applies its own pricing model at the query level, so
  tokens are not double-charged" [G13].
* Gemini 3 search billing started 2026-01-05 [G31].

### 3.4 Cost of thought signatures and caching

* "Thought signatures increase the input token count (and your cost) when you send them back" [G33].
* **Implicit caching** is on by default for Gemini 2.5+; min. prompt size **4,096 tokens** for 3.8/3.7/3.6/3.5 Flash and 3.1 Pro
  Preview; hits show up as `usageMetadata.cachedContentTokenCount`. Put large, stable content (system prompt + article/briefing)
  **first** and send similar prefixes close together [G32]. Explicit caching (`cachedContent`) is optional (Beta) [G32].

---

## 4. Request JSON: streaming chat ("Frag nach")

```http
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse
x-goog-api-key: <user key>
Content-Type: application/json
```

```json
{
  "systemInstruction": {
    "parts": [{ "text": "Du bist ein sachlicher Nachrichten-Erklärer. Antworte immer auf Deutsch (du), knapp und strukturiert. Nutze die Google-Suche nur für Hintergrund, der nicht im Kontext steht. Kennzeichne Unsicheres." }]
  },
  "contents": [
    {
      "role": "user",
      "parts": [
        { "text": "KONTEXT (Artikel)\nSchlagzeile: …\nLead: …\nText: …\nQuellen: …\nDatum: …" },
        { "text": "Heute ist Freitag, 9. Oktober 2026.\nFrage: Erklär mir den Hintergrund." }
      ]
    }
  ],
  "tools": [ { "googleSearch": {} } ],
  "generationConfig": {
    "thinkingConfig": { "thinkingLevel": "low" },
    "maxOutputTokens": 4096
  }
}
```

Field notes:
* `systemInstruction`: a `Content`, "Currently, text only" [G6]. Example form from the docs: `"system_instruction": {"parts": [{"text": "…"}]}` [G9].
* `contents[].role`: `"user"` or `"model"` [G6]. **The last non-empty turn must not be `model`** (prefill → HTTP 400 on 3.6+ and future models) [G5].
* Follow-up turns: append the previous model turn **as received** (including `thoughtSignature` fields) and the new user turn.
  For plain chat, signatures are "not strictly enforced" but omitting them "will degrade" quality [G17, G16]. Rules: don't merge a
  part that has a signature with one that hasn't; don't combine two signed parts [G12]. Practical approach: concatenate consecutive
  unsigned streamed text parts into one text part, keep every signed part as its own part (UNVERIFIED that merging unsigned
  streamed text is fine; the SDKs do equivalent accumulation).
* `thinkingConfig.thinkingLevel`: docs examples use lowercase `"low"`; enum in the reference is `MINIMAL|LOW|MEDIUM|HIGH` [G6, G9].
  `thinkingBudget` is legacy; **never both** (400) [G5, G17]. `includeThoughts: true` returns thought-summary parts flagged `"thought": true` [G6] → never show them as answer text.
* **Do not send** `temperature`, `topP`, `topK` ("deprecated and ignored … in future model generations, supplying these parameters returns an HTTP 400 error"), nor `candidateCount` ("unsupported in Gemini 3 and later") [G5].
* Optional `"store": false`: "Configures the logging behavior for a given request. If set, it takes precedence over the project-level logging config" [G6].
* Optional `safetySettings[]` (categories `HARM_CATEGORY_HATE_SPEECH`, `…_SEXUALLY_EXPLICIT`, `…_DANGEROUS_CONTENT`, `…_HARASSMENT`, `…_CIVIC_INTEGRITY`, `…_JAILBREAK`) [G6]. Defaults are fine for news.
* Without web search: just omit `tools`.

---

## 5. SSE format (generateContent)

### 5.1 Framing

* `alt=sse` turns the response into Server-Sent Events; the response body is "a stream of `GenerateContentResponse` instances" [G6, G9].
* The docs do **not** spell out the line framing for generateContent. Implement a generic SSE reader: collect `data:` lines until a
  blank line, the joined data is one JSON object. No `event:` names and no `[DONE]` sentinel are documented for this endpoint →
  **end of stream = EOF** (UNVERIFIED for success streams; no real key was used).
* **Check the HTTP status before parsing SSE.** OBSERVED (P1): an invalid key on `:streamGenerateContent?alt=sse` returns
  **HTTP 400 with `Content-Type: text/event-stream` but a plain JSON error body** (not `data:`-framed). So: `if (!response.isSuccessful) parseErrorJson(body.string())`.
* Mid-stream error format: **not documented** for generateContent. Defensively treat any `data:` JSON with a top-level `"error"` as an error.

### 5.2 What each chunk contains [G6]

```text
GenerateContentResponse
├─ candidates[0]
│  ├─ content.role = "model"
│  ├─ content.parts[]            ← new parts of this chunk (text deltas, functionCall, toolCall, toolResponse, thought parts)
│  │    ├─ text                  ← append to answer (may be "" and only carry a thoughtSignature)
│  │    ├─ thought: true         ← thought summary (only with includeThoughts) → don't show as answer
│  │    ├─ thoughtSignature      ← opaque base64; keep with its part for history replay
│  │    ├─ functionCall {id, name, args}         ← arrives COMPLETE in one chunk (no arg deltas) [G28]
│  │    ├─ toolCall {id, toolType, toolName?, args}      ← only with includeServerSideToolInvocations
│  │    └─ toolResponse {id, toolType, response}         ← only with includeServerSideToolInvocations
│  ├─ groundingMetadata          ← see §6.3; may come in several chunks
│  ├─ finishReason               ← empty while generating; set on the final chunk
│  ├─ finishMessage              ← "populated only when finishReason is set"
│  ├─ safetyRatings[], citationMetadata (recitation sources), urlContextMetadata, index
├─ promptFeedback.blockReason   ← prompt blocked → no candidates
├─ usageMetadata                 ← token counts (keep the last one seen)
├─ modelVersion, responseId, modelStatus{modelStage, retirementTime, message}
```

Shape of a final chunk (illustrative, assembled from the reference fields; **not** a captured response):

```json
{
  "candidates": [{
    "content": { "role": "model", "parts": [ { "text": "… letzter Satz.", "thoughtSignature": "CoMDAXLI…" } ] },
    "finishReason": "STOP",
    "groundingMetadata": {
      "webSearchQueries": ["…"],
      "searchEntryPoint": { "renderedContent": "<style>…</style><div>…</div>" },
      "groundingChunks": [ { "web": { "uri": "https://vertexaisearch.cloud.google.com/…", "title": "tagesschau.de" } } ],
      "groundingSupports": [ { "segment": { "partIndex": 0, "startIndex": 0, "endIndex": 85, "text": "…" }, "groundingChunkIndices": [0] } ]
    }
  }],
  "usageMetadata": { "promptTokenCount": 2412, "cachedContentTokenCount": 0, "candidatesTokenCount": 380, "thoughtsTokenCount": 210, "toolUsePromptTokenCount": 0, "totalTokenCount": 3002 },
  "modelVersion": "gemini-3.8-flash",
  "responseId": "…"
}
```

### 5.3 `finishReason` values [G6]

`STOP` (normal) · `MAX_TOKENS` (→ "Antwort gekürzt") · `SAFETY` · `RECITATION` · `LANGUAGE` · `OTHER` · `BLOCKLIST` ·
`PROHIBITED_CONTENT` · `SPII` · `MALFORMED_FUNCTION_CALL` · `UNEXPECTED_TOOL_CALL` (tool call but no tools enabled) ·
`TOO_MANY_TOOL_CALLS` · `MISSING_THOUGHT_SIGNATURE` · `MALFORMED_RESPONSE` · `ESCALATION` · `PUP_LIMITED_DISABLED`
(account limited for Prohibited Use Policy violations) · image-only values (`IMAGE_SAFETY`, `IMAGE_PROHIBITED_CONTENT`, `IMAGE_OTHER`, `NO_IMAGE`, `IMAGE_RECITATION`).

`promptFeedback.blockReason`: `SAFETY`, `OTHER`, `BLOCKLIST`, `PROHIBITED_CONTENT`, `IMAGE_SAFETY` → "returns no candidates" [G6].

### 5.4 Parser (Kotlin sketch, OkHttp 5, no okhttp-sse needed)

```kotlin
val call = client.newCall(request)            // readTimeout(0) or generous: thinking/search can delay the first byte
call.execute().use { resp ->
    if (!resp.isSuccessful) throw GeminiError.from(resp.code, resp.body.string())   // §12: body may be {..} or [{..}]
    val src = resp.body.source()
    val data = StringBuilder()
    while (true) {
        val line = src.readUtf8Line() ?: break                 // EOF = end of stream
        when {
            line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
            line.isEmpty() && data.isNotEmpty() -> { handleChunk(json.parseToJsonElement(data.toString()).jsonObject); data.clear() }
            else -> Unit                                         // ignore comments / unknown fields
        }
    }
    if (data.isNotEmpty()) handleChunk(json.parseToJsonElement(data.toString()).jsonObject)
}
// handleChunk: error? → throw; promptFeedback.blockReason → Blocked;
// for part in candidates[0].content.parts: thought==true → skip; text → emit delta; keep thoughtSignature with part;
//   functionCall → collect; toolCall(GOOGLE_SEARCH_WEB) → status "Suche: args.queries"; toolResponse → keep search_suggestions;
// groundingMetadata → accumulate (§6.3); finishReason → remember; usageMetadata → overwrite.
// No finishReason at EOF → treat as interrupted.
```

---

## 6. Web search tool (Grounding with Google Search)

### 6.1 Definition [G6, G10]

```json
"tools": [ { "googleSearch": {} } ]
```

`GoogleSearch` fields (API reference only, no guide coverage):

| Field | Type | Meaning |
|---|---|---|
| `timeRangeFilter` | `Interval {startTime, endTime}` (RFC 3339, start inclusive, end exclusive) | "Filter search results to a specific time range. If customers set a start time, they must set an end time (and vice versa)." |
| `searchTypes` | `{ "webSearch": {} , "imageSearch": {} }` | "If not set, web search is enabled by default." Image search returns image bytes |

* Legacy tool `googleSearchRetrieval` (+ `dynamicRetrievalConfig`) is for older models; "For all current models, use the
  `google_search` tool" [G10, G6]. (Note: the migration guide's "before" example still shows `googleSearchRetrieval` [G28] – ignore.)
* Supported (generateContent page): Gemini 3.8 / 3.7 / 3.6 / 3.5 Flash, 3.5 / 3.1 Flash-Lite, 3.1 Pro Preview, 3 Flash Preview, image models, 2.5 family [G10].
* The model decides whether and how often to search (one or more queries) [G10]. There is no `max_uses` parameter.
* `toolConfig.retrievalConfig` {`latLng`, `languageCode` (BCP-47)} exists ("The location of the user", "The language code of the user") [G7] –
  documented for retrieval/Maps; effect on Google Search not documented (could pass `"languageCode": "de-DE"`, UNVERIFIED benefit).
* Tool-combination caveat: "Built-in tools like google_search rely on location and current time information, so if your
  system_instruction or function_declaration.description has conflicting location and time information, the tool combination
  feature might not work well" [G13] → give the date plainly, don't contradict.

### 6.2 `groundingMetadata` fields [G6, G10]

| Field | Meaning |
|---|---|
| `webSearchQueries[]` | Queries the model ran (use for "Suche: …" status and for the search-cost count) |
| `searchEntryPoint.renderedContent` | **HTML + CSS of the Search Suggestions widget** ("required Search Suggestions", [G10]); `sdkBlob` = base64 JSON array of `<search term, search url>` tuples |
| `groundingChunks[]` | Union: `web {uri, title}` / `image` / `retrievedContext` / `maps`. **`web.uri` is a `https://vertexaisearch.cloud.google.com/…` redirect URL and `web.title` is the domain** (e.g. `"aljazeera.com"`) in the docs example [G10] |
| `groundingSupports[]` | `segment {partIndex, startIndex, endIndex, text}` + `groundingChunkIndices[]` + `confidenceScores[]` + `renderedParts[]` |
| `imageSearchQueries[]`, `retrievalMetadata.googleSearchDynamicRetrievalScore`, `googleMapsWidgetContextToken` | not needed |

### 6.3 How citations come back (and streaming rules) [G6]

* `groundingChunks`: "When streaming, this only contains the grounding chunks that have not been included in the grounding
  metadata of previous responses." → **append** chunks in arrival order into one list.
* `groundingChunkIndices`: "If the response is streaming, the groundingChunkIndices refer to the indices across all responses.
  It is the client's responsibility to accumulate the grounding chunks from all responses (while maintaining the same order)."
* `segment.startIndex/endIndex` are **byte offsets** ("measured in bytes") within the Part → UTF-8 bytes, not Kotlin `String`
  indices (umlauts!). Easier: locate `segment.text` in the answer, or just show a source list.
* For the app's "Quellen" chips: dedupe `groundingChunks[].web` by `uri`; label = `title` (a domain, not the article title).
* Link handling must respect the Terms (§11): open the link as-is; don't crawl/scrape/resolve links programmatically, no click tracking.

### 6.4 Search Suggestions display (required)

* Gemini docs: `searchEntryPoint` "Contains the HTML and CSS to render the required Search Suggestions. Full usage requirements
  are detailed in the Terms of Service" [G10].
* Terms: "will only display the Grounded Results with the associated Search Suggestion(s) to the end user who submitted the prompt" [G24].
* Display rules from Google's Vertex AI page for the same feature [G35]: "Display the Search Suggestion exactly as provided, and
  don't make any modifications to colors, fonts, or appearance"; the suggestion must stay visible whenever the grounded response
  is shown; the chip container must be the same width as the grounded response; light/dark handled by the HTML's
  `prefers-color-scheme`; a tap must go directly to the Google results page (in-app browser or browser), no interstitials.
* Android: render `renderedContent` in a `WebView` under the answer (transparent background, JS not required per se – UNVERIFIED),
  intercept link taps and open them in a Custom Tab / browser.

---

## 7. Function calling

### 7.1 Declaration [G6, G12]

```json
"tools": [{
  "functionDeclarations": [{
    "name": "publish_articles",
    "description": "Veröffentlicht die fertig recherchierten Live-Artikel … genau einmal am Ende aufrufen, auch mit leerer Liste.",
    "parametersJsonSchema": {
      "type": "object",
      "properties": {
        "articles": { "type": "array", "description": "Alle fertigen Artikel, wichtigste zuerst", "items": { "...": "articleSchema" } }
      },
      "required": ["articles"],
      "additionalProperties": false
    }
  }]
}]
```

* `name`: a-z, A-Z, 0-9, `_ : . -`, max 128 [G6].
* Two schema fields, mutually exclusive [G6]:
  * `parameters`: OpenAPI 3.0.3 **subset** `Schema` (`type` enum `STRING|NUMBER|INTEGER|BOOLEAN|ARRAY|OBJECT|NULL`, `format`, `nullable`,
    `enum`, `min/maxItems`, `properties`, `required`, `min/maxLength`, `pattern`, `anyOf`, `propertyOrdering`, `items`, `minimum`, `maximum`, …).
    **No `additionalProperties`** in this subset.
  * `parametersJsonSchema`: JSON Schema; the reference example uses `additionalProperties: false` and `propertyOrdering`.
  → **Use `parametersJsonSchema` for our existing `LivePublish` schema** (it relies on `additionalProperties: false`).
* `format`: "Any value is allowed, but most do not trigger any special functionality" [G6] → `"format": "uri"` is harmless; the
  structured-output docs name `date-time`, `date`, `time` for strings [G15].
* Null: JSON-Schema style `{"type": ["string","null"]}` is documented for structured output [G15]. A bare `{"type":"null"}`
  (our `image` field) is not explicitly documented for `parametersJsonSchema` → **drop `image` from the Gemini variant** (app sets it to null anyway).
* "For ANY mode, the API may reject very large or deeply nested schemas" [G12]. Keep descriptions short; ideally ≤ 10–20 tools [G12].

### 7.2 Modes (`toolConfig.functionCallingConfig`) [G7, G12]

| Mode | Behavior |
|---|---|
| `AUTO` | Default when only function declarations are present; model chooses text or call. **Not supported with tool combination** |
| `VALIDATED` | "Default mode for tool combination (when built-in tools or structured outputs also enabled)": text or call, calls schema-validated (constrained decoding) |
| `ANY` | Must call a function; with `allowedFunctionNames` restricted to that list |
| `NONE` | No function calls |

`allowedFunctionNames[]` "should only be set when the Mode is ANY or VALIDATED" [G7].

### 7.3 The call (model part) [G6, G12, G16]

```json
{ "functionCall": { "id": "m4q8z1v6", "name": "publish_articles", "args": { "articles": [ … ] } }, "thoughtSignature": "…" }
```

* Gemini 3 "always returns a unique `id` with every `functionCall`" [G12]. In streaming, the call arrives complete in one chunk [G28].
* Parallel calls: only the **first** `functionCall` part carries the `thoughtSignature` [G16].
* With tool combination, `functionCall`, `toolCall` and `toolResponse` parts can be mixed → "don't assume the functionCall will
  always be the last item in the parts array" [G12].
* `finishReason: MALFORMED_FUNCTION_CALL` = invalid call [G6]. Known trigger: requiring structured text right before a tool call;
  fix by not demanding pre-tool text [G12].

### 7.4 The result (next request) [G6, G13, G16, G5]

```json
{
  "contents": [
    { "role": "user",  "parts": [ { "text": "…ursprüngliche Anfrage…" } ] },
    { "role": "model", "parts": [ /* ALL parts of the model turn exactly as received, incl. thoughtSignature, toolCall, toolResponse */ ] },
    { "role": "user",  "parts": [ { "functionResponse": { "id": "m4q8z1v6", "name": "publish_articles", "response": { "result": "Nicht veröffentlicht – bitte korrigieren: …" } } } ] }
  ],
  "tools": [ … same as before … ],
  "toolConfig": { … same as before … }
}
```

* `functionResponse.response` is a JSON object with free keys ("output", "result", …; use `"error"` for failures) [G6].
* Gemini 3 rules: include the **same `id` and `name`**, exactly **one response per call**; on generateContent mismatches don't
  error but "cause the model to return empty responses with finish_reason: STOP" [G5]. Role of the response turn: `user` (all doc
  examples; `Content.role` "Must be either 'user' or 'model'", although the `Tool` reference text still mentions a "function" role) [G6, G12, G13].
* Parallel calls: send back "FC1+signature, FC2" then "FR1, FR2" – interleaving FC/FR gives 400 [G16].
* Missing signature on the first `functionCall` of any step in the current turn → **HTTP 400** ("Function call … is missing a
  thought_signature") [G16]; required "even when thinking level is set to minimal" [G17].
* Extra instructions with a function result: append to the response text separated by `\n\n`, not as extra parts [G5].
* Dummy signature for injected (not model-generated) calls: `"context_engineering_is_the_way_to_go"` or `"skip_thought_signature_validator"` [G16].

---

## 8. LIVE NEWS: search + `publish_articles`

### 8.1 Rules (Gemini 3, generateContent) [G13, G12, G15]

* Combination of built-in tools and custom functions: **Preview, "supported for Gemini 3 models only"** [G13].
* Required: `"toolConfig": {"includeServerSideToolInvocations": true}` ("You must set the include_server_side_tool_invocations flag
  to true to enable tool context circulation") **plus** the `functionDeclarations` [G13]. Reference: "If true, the API response will
  include the server-side tool calls and responses within the Content message" [G7].
* Mode: `VALIDATED` (default for combination; **AUTO not supported**) [G13, G12]. Whether `ANY` works together with built-in tools is not documented.
* The response returns `toolCall` + `toolResponse` parts for each search (`toolType: "GOOGLE_SEARCH_WEB"`, `args.queries`,
  `response.search_suggestions`) and a `functionCall` part for `publish_articles`; all parts carry `id` and `thoughtSignature`
  and **must be returned unchanged** if you continue the conversation [G13].
* `ToolType` enum: `GOOGLE_SEARCH_WEB`, `GOOGLE_SEARCH_IMAGE`, `URL_CONTEXT`, `GOOGLE_MAPS`, `FILE_SEARCH` [G6].
* Tokens: `toolCall`/`toolResponse` parts you send back count toward `promptTokenCount`, except Google Search [G13].
* Whether `groundingMetadata` (`groundingChunks`) is also returned in this mode is **not documented** (Open question).

### 8.2 One-request pattern (recommended for 3.8 Flash / 3.7 / 3.6 / 3.5 / 3.1 Pro)

```http
POST …/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse
```

```json
{
  "systemInstruction": { "parts": [{ "text": "Du bist Nachrichtenredakteur … Suche mit Google nach Meldungen seit <Zeitpunkt> zu den Themen. Prüfe an ≥ 2 Quellen. Rufe am Ende GENAU EINMAL publish_articles auf, auch mit leerer Liste. Schreibe keinen Text vor dem Tool-Aufruf." }] },
  "contents": [ { "role": "user", "parts": [ { "text": "Jetzt: 2026-10-09T08:00:00+02:00. Stand Morgen-Briefing: 2026-10-09T06:00:00+02:00.\nThemen: politik (Politik), wirtschaft (Wirtschaft), …" } ] } ],
  "tools": [
    { "googleSearch": { "timeRangeFilter": { "startTime": "2026-10-08T22:00:00Z", "endTime": "2026-10-09T06:00:01Z" } } },
    { "functionDeclarations": [ { "name": "publish_articles", "description": "…", "parametersJsonSchema": { "…": "§7.1" } } ] }
  ],
  "toolConfig": {
    "includeServerSideToolInvocations": true,
    "functionCallingConfig": { "mode": "VALIDATED", "allowedFunctionNames": ["publish_articles"] }
  },
  "generationConfig": { "thinkingConfig": { "thinkingLevel": "medium" }, "maxOutputTokens": 32768 }
}
```

Handling:
1. Stream: show "Suche: …" from `toolCall.args.queries` (and/or `groundingMetadata.webSearchQueries`), keep `toolResponse.response.search_suggestions` (HTML, display duty §11).
2. Collect the `functionCall` (complete in one chunk), `LivePublish.validate(args)`.
3. Valid → done (no follow-up needed; nothing forces you to send a `functionResponse`). Problems → follow-up request (§7.4) with the
   whole model turn + `functionResponse {id, name, response: {result: "<problems>"}}`; same tools/toolConfig; cap retries.
4. No `functionCall` but `STOP` → model answered in text: treat as "no result", optionally one reminder turn.
5. `timeRangeFilter`: documented only in the API reference; effect on news freshness untested (UNVERIFIED). If it causes errors
   or empty results, drop it and rely on the prompt. `allowedFunctionNames` is optional (allowed with `VALIDATED` [G7]); drop it
   if it turns out to suppress the search.
6. Sources in `articles[].sources[]` are model-written. Prefer URLs that also appear in `groundingChunks` if those are present.

### 8.3 Fallback A: Structured output + Google Search (no function) [G15]

Preview, **only `gemini-3.1-pro-preview` and `gemini-3.8-flash`** (generateContent docs):

```json
{
  "contents": [ … ],
  "tools": [ { "googleSearch": {} } ],
  "generationConfig": {
    "responseFormat": { "text": { "mimeType": "application/json", "schema": { "type": "object", "properties": { "articles": { "…": "…" } }, "required": ["articles"] } } }
  }
}
```

* `responseFormat.text.{mimeType, schema}` is the current field; `responseSchema`/`responseMimeType`-only schemas are **deprecated** ("Use responseFormat instead") [G6].
* Streaming: "The streamed chunks will be valid partial JSON strings, which can be concatenated to form the final, complete JSON object" [G15].
* Grounding metadata of the same response can back the sources.

### 8.4 Fallback B: documented two-step pattern (works on every 3.x model incl. Flash-Lite)

1. **Research call:** `streamGenerateContent` with `tools: [{googleSearch:{…}}]` only, prompt "Recherchiere … und fasse die
   Meldungen mit Fakten, Zeitpunkten und Quellen zusammen". Keep text + `groundingChunks` + `searchEntryPoint`.
2. **Publish call:** `generateContent` **without** search, `functionDeclarations: [publish_articles]`,
   `toolConfig.functionCallingConfig: {"mode": "ANY", "allowedFunctionNames": ["publish_articles"]}` [G7, G12], input = research text +
   numbered source list (title + uri). The model must call `publish_articles`.
* Search is billed only in step 1. Costs one extra round trip.
* The Terms explicitly allow this: you may store "temporarily for the purpose of resubmitting the text of the Grounded Result in a
  subsequent prompt that you submit to Google via a function call to obtain a refined or improved Grounded Result", provided you don't
  use interim results otherwise, delete undisplayed ones, and display the Search Suggestions with the final result [G24].

---

## 9. Usage, cost and caching

### 9.1 `usageMetadata` (generateContent) [G6]

| Field | Meaning |
|---|---|
| `promptTokenCount` | Prompt size; **includes** cached tokens ("still the total effective prompt size") |
| `cachedContentTokenCount` | Cached part of the prompt (implicit or explicit cache hits) |
| `candidatesTokenCount` | Generated answer tokens |
| `thoughtsTokenCount` | Thinking tokens (billed as output [G3, G33]) |
| `toolUsePromptTokenCount` | "Number of tokens present in tool-use prompt(s)" |
| `totalTokenCount` | prompt + thoughts + candidates |
| `promptTokensDetails[]`, `cacheTokensDetails[]`, `candidatesTokensDetails[]`, `toolUsePromptTokensDetails[]` | per-modality `{modality, tokenCount}` |
| `serviceTier` | tier used |

No cost field, no search count. In streaming, keep the **last** `usageMetadata` (UNVERIFIED whether intermediate chunks carry partial values).

### 9.2 Cost formula for `AiPricing` (app-side estimate)

```
input   = (promptTokenCount − cachedContentTokenCount) × inPrice
cached  = cachedContentTokenCount × cachePrice                          (storage only for explicit caches)
output  = (candidatesTokenCount + thoughtsTokenCount) × outPrice
tooluse = toolUsePromptTokenCount × inPrice                             (UNVERIFIED whether billed; conservative)
search  = distinctNonEmpty(webSearchQueries ∪ toolCall[GOOGLE_SEARCH_WEB].args.queries) × $0.014
          (after 5,000 free/month shared across Gemini 3.x; the app can't see the remaining allowance → count conservatively or make it a setting)
```

* Prices depend on **date** (3.8/3.7/3.6 Flash double on 2027-01-01) and, for 3.1 Pro, on prompt size (> 200k) [G3].
* Rough per-run estimate (UNVERIFIED assumption: 20k input, 8k output+thinking, 10 search queries, 3.8 Flash intro price):
  $0.015 + $0.03 + $0.14 = **≈ $0.19 per Live run** once the free 5,000 queries are used; searches dominate.

---

## 10. Alternative: Interactions API (`POST /v1beta/interactions`)

Use this if Google stops shipping new models on generateContent ("all new models, multimodal capabilities, tools, and agentic
features will launch on the Interactions API" [G25]).

### 10.1 Request (chat) [G26, G27, G11]

```http
POST https://generativelanguage.googleapis.com/v1beta/interactions
x-goog-api-key: <key>
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "model": "gemini-3.8-flash",
  "system_instruction": "Du bist ein sachlicher Nachrichten-Erklärer …",
  "input": [
    { "type": "user_input", "content": [ { "type": "text", "text": "KONTEXT (Artikel) …" }, { "type": "text", "text": "Frage: …" } ] }
  ],
  "tools": [ { "type": "google_search" } ],
  "generation_config": { "thinking_level": "low", "max_output_tokens": 4096 },
  "stream": true,
  "store": false
}
```

* `input`: string, Content, Content[] or **Step[]**; for stateless multi-turn pass the previous `steps` and append a `user_input` step [G26, G29].
* **`store` defaults to true** (interactions kept 55 days paid / 1 day free) → send `"store": false` (then `previous_interaction_id` can't be used) [G25].
* `system_instruction` is a **string**; `generation_config` has `max_output_tokens`, `seed`, `stop_sequences`, `thinking_level` (`minimal|low|medium|high`),
  `thinking_summaries` (`auto|none`), `tool_choice` (`auto|any|none|validated` or `{"allowed_tools": {"mode", "tools": [names]}}`) – **no temperature** [G26].
* Tools: `{"type":"google_search","search_types":["web_search"]}` (**no time-range filter** here), `{"type":"function","name","description","parameters"}` [G26].
* Structured output: top-level `"response_format": {"type":"text","mime_type":"application/json","schema":{…}}` [G29, G15].
* Not supported vs generateContent: Batch, explicit caching, **custom safety settings** [G25].
* Path: reference and streaming guide use `/v1beta/interactions`; the migration guide also shows `/v1beta2/interactions` → use `v1beta` [G26, G27, G28].

### 10.2 SSE events [G26, G27]

Every event: `event: <name>` + `data: {… "event_type": "<name>" …}`; stream ends with **`event: done` / `data: [DONE]`** [G27].
Dispatch on `event_type`; "handle unknown event types gracefully—log and skip" [G27].

| `event_type` | Payload fields to read |
|---|---|
| `interaction.created` | `interaction.{id, status, model}` |
| `interaction.status_update` | `interaction_id`, `status` (`in_progress`, `requires_action`, `completed`, `failed`, `cancelled`, `incomplete`, `queued`) |
| `step.start` | `index`, `step.type` (`model_output`, `thought`, `function_call` (+`id`,`name`,`arguments:{}`), `google_search_call` (+`id`), `google_search_result` (+`call_id`), …) |
| `step.delta` | `index`, `delta.type`: `text` {`text`} · `text_annotation_delta` {`annotations[]`: `url_citation` {`url`,`title`,`start_index`,`end_index` (bytes)}} · `thought_summary` {`content{type,text}`} · `thought_signature` {`signature`} · **`arguments_delta` {`arguments`: partial JSON string → accumulate}** · `google_search_call` {`arguments.queries`, `signature`} · `google_search_result` {`result[].search_suggestions`, `is_error`, `signature`} |
| `step.stop` | `index` |
| `interaction.completed` | `interaction.status` (`completed` / **`requires_action`** when a function call waits / `incomplete` e.g. max tokens), `interaction.usage` |
| `error` | `error.{code, message}` (snake_case codes, §12.3) |

The breaking-changes guide additionally names `interaction.in_progress` / `interaction.requires_action` events [G29] while the
reference and streaming guide use `interaction.status_update` [G26, G27] → handle both by `event_type`, ignore unknown.

### 10.3 Usage (Interactions) [G26, G27]

`usage.total_input_tokens`, `total_output_tokens`, `total_thought_tokens`, `total_cached_tokens`, `total_tool_use_tokens`,
`total_tokens`, `input_tokens_by_modality[]`, `output_tokens_by_modality[]`, `cached_tokens_by_modality[]`,
`tool_use_tokens_by_modality[]`, and **`grounding_tool_count[] {type: "google_search"|"google_maps"|"retrieval", count}`**
(→ exact search count). Still no cost field. (Migration-guide examples with `prompt_tokens`/`completion_tokens` are outdated.)

### 10.4 Live News on Interactions [G14, G27]

`tools: [{"type":"google_search"}, {"type":"function","name":"publish_articles",…}]`, `generation_config.tool_choice: "validated"`
(auto not supported in combination), `stream: true`, `store: false`. Read `step.start` (type `function_call`, `id`, `name`), accumulate
`arguments_delta.arguments`, the run ends with `interaction.completed` status `requires_action` → validate. Correction loop needs
the steps (incl. `signature`s) resent as `input` plus `{"type":"function_result","call_id":…,"name":…,"result":[{"type":"text","text":"…"}]}`
(stateless) [G14, G26].

---

## 11. Terms that matter for displaying grounded results [G24, G35]

From "Grounding with Google Search" in the Gemini API Additional Terms (paraphrased, see source):
* Use only "in an application that is owned and operated by you" and "only display the Grounded Results with the associated Search
  Suggestion(s) to the end user who submitted the prompt".
* No caching, framing, syndicating, reselling, analyzing, training on Grounded Results or Search Suggestions; no programmatic
  collection of Links, no index building, no using Links to find pages to crawl/scrape.
* No copying/storing or click/link tracking, **except**: store Grounded Result text up to **2 years** (1) to evaluate display, (2) "in chat
  history of an end user of your application only for the purpose of allowing that end user to view their chat history", (3) temporarily
  for resubmission via function call (§8.4); store for legal compliance; let end users copy/store individual results (not automated).
* Don't modify or intersperse other content with Grounded Results/Search Suggestions; no interstitials between a Link/Suggestion and
  its destination; don't hinder the destination page.
* Google stores prompts, context and output for **30 days** for grounding (debugging/testing).
* Display details (Vertex page): show suggestions unmodified, same width as the answer, always visible with the answer, tap → Google results directly [G35].

App consequences (not legal advice): show `renderedContent` / `search_suggestions` with every grounded chat answer and with the
Live results of that run; keep grounded chat answers only as the user's chat history; Live articles stored locally for the same user
fall under "view their history" at best – keep the run's suggestion HTML with them and don't reuse the text elsewhere.

---

## 12. Error mapping

### 12.1 Format (generateContent) [G18, P1]

```json
{ "error": { "code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT",
  "details": [ { "@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "API_KEY_INVALID", "domain": "googleapis.com", "metadata": { "service": "generativelanguage.googleapis.com" } },
               { "@type": "type.googleapis.com/google.rpc.LocalizedMessage", "locale": "en-US", "message": "API key not valid. Please pass a valid API key." } ] } }
```

OBSERVED 2026-10-09 (P1):
* fake `AIza…`-style key → **400 `INVALID_ARGUMENT`, reason `API_KEY_INVALID`** (also on `:streamGenerateContent?alt=sse`, with `Content-Type: text/event-stream` but a plain JSON body).
* fake `AQ.…`-style key → **401 `UNAUTHENTICATED`, reason `ACCESS_TOKEN_TYPE_UNSUPPORTED`**, message "Request had invalid authentication credentials. Expected OAuth 2 access token …".
* no key → **403 `PERMISSION_DENIED`** "Method doesn't allow unregistered callers …".
* `POST /v1beta/interactions` with a fake key → 400, google.rpc format **wrapped in a JSON array** `[{"error":{…}}]`.

Parse: accept object or array; read `error.code` (HTTP), `error.status`, `details[@type=ErrorInfo].reason`.

### 12.2 Mapping (generateContent) [G18, G20, G21, G23]

| HTTP | `status` / reason | Meaning | App reaction |
|---|---|---|---|
| 400 | `INVALID_ARGUMENT` + `API_KEY_INVALID` | Invalid key | "API-Key ungültig" → settings, no retry |
| 401 | `UNAUTHENTICATED` + `ACCESS_TOKEN_TYPE_UNSUPPORTED` | Key not taken as an API key (mistyped `AQ.` key, or a new auth key Google rejects, §12.4) | AUTH_KEY_REJECTED text + "Neuen Key in AI Studio", key marked rejected |
| 401 | `UNAUTHENTICATED` (other) | Invalid key | "API-Key ungültig" |
| 400 | `INVALID_ARGUMENT` (other) | Malformed request: schema, `minimal` thinking on 3.8/3.7/3.1 Pro, `thinkingLevel`+`thinkingBudget`, prefilled model turn, missing thought signature, interleaved FC/FR; or a message about the model (`unexpected model name format`, `not supported`) → model fallback (§12.5) | log `message`, no retry |
| 400 | `FAILED_PRECONDITION` | "Gemini API free tier is not available in your country. Please enable billing" | FREE_TIER_UNAVAILABLE text + "Abrechnung in AI Studio" |
| 402 | (doc table says `RESOURCE_EXHAUSTED`) | **Prepay credit balance depleted** – all keys of that billing account stop | "Guthaben aufgebraucht", **don't retry** |
| 401/403 | reason `API_KEY_SERVICE_BLOCKED` / `SERVICE_DISABLED` | Gemini API blocked for the key / not enabled in its project (§12.4) | API_BLOCKED text + "Gemini API aktivieren" |
| 403 | reason `API_KEY_ANDROID_APP_BLOCKED` / `…_IOS_APP_…` / `…_HTTP_REFERRER_…` / `…_IP_ADDRESS_BLOCKED` | Application restriction of the key does not fit | KEY_RESTRICTED text + "Key-Beschränkung prüfen" |
| 403 | message "reported as leaked" | Key blocked as leaked [G20] | KEY_LEAKED text + "Neuen Key in AI Studio", key marked rejected |
| 403 | `PERMISSION_DENIED`, message about the model | Project may not use this model | model fallback (§12.5) |
| 403 | `PERMISSION_DENIED` (other) | Key lacks permission / no key | "Der Key darf die Gemini API nicht nutzen." |
| 404 | `NOT_FOUND` | Unknown model or resource; new projects do not get older models [G30] | model fallback (§12.5), then "Modell nicht verfügbar" |
| 429 | `RESOURCE_EXHAUSTED` + `QuotaFailure` with `quotaValue` "0" (or "limit: 0") | The tier offers this model no quota at all (not the search's quota) | model fallback (§12.5) |
| 429 | `RESOURCE_EXHAUSTED` | Rate limit: RPM, TPM, RPD **or spend-based** limit (rolling 10 min) | exponential backoff + jitter (1 s, 2 s, 4 s, 8 s), max ~4 tries; then "Limit erreicht" (free tier: quota text, retried only for `RetryInfo` ≤ 20 s) |
| 499 | `CANCELLED` | Client closed | ignore |
| 500 | `INTERNAL` | Server error (doc example: input context too long) | retry once, then message |
| 503 | `UNAVAILABLE` | **"temporarily overloaded or down"** | backoff retry |
| 504 | `DEADLINE_EXCEEDED` | Too slow (large prompt) | retry once / shorten |

* Retry only 429, 408, 5xx; never 400/402/403 [G20].
* **RPM vs RPD vs spend 429**: the generateContent docs give no reason codes to tell them apart (Open question). The Interactions API does (`rate_limit_exceeded` vs `quota_exceeded`).
* In-band (HTTP 200) problems: `promptFeedback.blockReason`, `finishReason` (§5.3). Search/tool errors in Interactions: `google_search_result.is_error`.

### 12.3 Interactions error codes [G19]

`invalid_request` 400 · `failed_precondition` 400 (e.g. disabled billing) · `parameter_unknown` 400 · `out_of_range` 416 ·
`authentication` 401 · **`payment_required` 402** (Prepay depleted) · `permission_denied` 403 · `not_found` / `model_not_found` 404 ·
`already_exists` / `aborted` 409 · **`rate_limit_exceeded` 429** (per-minute/second) · **`quota_exceeded` 429** (daily) ·
`too_many_requests` 429 · `cancelled` 499 · `api_error` 500 · `unimplemented` 501 · **`service_unavailable` 503** (overloaded) ·
`deadline_exceeded` 504. Blocked: `safety`, `recitation`, `language`, `prohibited_content`, `spii`, `blocklist`, `content_blocked`, image_*.
Generation errors: `malformed_function_call`, `malformed_tool_call`, `unexpected_tool_call`, `too_many_tool_calls`, `missing_thought_signature`.
Streaming errors arrive as `event_type: "error"`. (But see §12.1: auth errors were observed in google.rpc format.)

### 12.4 Why a key "does nothing": the known causes (app texts, v1.3.1)

A user report on 2026-10-09 ("new `AQ.` key, nothing works") had five plausible causes; the official docs document only some
of them, the rest come from forum threads (F1) and the Google Cloud error model. Every cause is decided by the google.rpc
`ErrorInfo.reason` (or the status) — **not by the HTTP code alone**: the same reason came as 403 and, with the key sent as a
bearer token, as 401 [F1, post of 2026-10-08].

| Cause | Signal | Source | App (`AiError.GeminiAccess` / key test) | Link |
|---|---|---|---|---|
| (a) Google does not take the auth key | 401 `UNAUTHENTICATED` + `ACCESS_TOKEN_TYPE_UNSUPPORTED` | P1 (fake key), F1 (real keys AI Studio shows as valid; recreating the key sometimes helps; **no official fix**, no Google staff answer) | AUTH_KEY_REJECTED: „Google nimmt deinen Key gerade nicht an. Das passiert derzeit bei manchen neuen Keys („AQ.…“), auch wenn AI Studio sie als gültig zeigt: Erstell in AI Studio einen neuen Key und prüf, dass sein Projekt die Gemini API nutzen darf.“ Key marked rejected. | https://aistudio.google.com/apikey |
| (b) API blocked / disabled for the project | reason `API_KEY_SERVICE_BLOCKED` (403; 401 seen with a bearer header) or `SERVICE_DISABLED` (403, with a `Help` link to the activation page) | F1; G22 ("if you don't select the Generative Language API, Gemini requests with that key will fail"); Cloud error model | API_BLOCKED: „Die Gemini API ist für dein Google-Projekt gesperrt oder nicht aktiviert. …“; „Ohne Test speichern“ offered | https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com |
| (b′) Key restricted to other apps | reason `API_KEY_ANDROID_APP_BLOCKED`, `API_KEY_IOS_APP_BLOCKED`, `API_KEY_HTTP_REFERRER_BLOCKED`, `API_KEY_IP_ADDRESS_BLOCKED` | Cloud error model (UNVERIFIED for Gemini) | KEY_RESTRICTED | https://console.cloud.google.com/apis/credentials |
| (b″) Key blocked as leaked | message "Your API key was reported as leaked. Please use another API key." (status not documented) | G20 | KEY_LEAKED: „Google hat deinen Key gesperrt, weil er öffentlich aufgetaucht ist. …“ Key marked rejected. | https://aistudio.google.com/apikey |
| (c) Free tier not available for the country/project | 400 `FAILED_PRECONDITION` "free tier is not available in your country … enable billing" | G18 (solution: "Set up a paid plan through Google AI Studio"). G37 lists Germany, all EU states, CH, UK, NO, IS, LI as available regions (LU 2026-04-28) but says nothing about the free tier per country | FREE_TIER_UNAVAILABLE: „Der kostenlose Gemini-Tarif ist für dein Land/Projekt nicht freigeschaltet – aktivier die Abrechnung in AI Studio oder wähl einen anderen Anbieter.“; „Ohne Test speichern“ offered | https://aistudio.google.com/projects (Billing Tier column, "Set up billing" [G23]) |
| (d) Model not open to the project | 404 `NOT_FOUND`; 400/403 whose message is about the model; 429 with a `QuotaFailure.quotaValue` of "0" (free tier offers the model no quota) | G30 (2.5 models only for previous users; "for any new projects, use … 3.5 Flash-Lite or 3.8 Flash"), forum F1 ("2.5-flash may not be enabled for new projects") | automatic model fallback (§12.5), then `ModelUnavailable` | – |
| (e) Quota | 429 `RESOURCE_EXHAUSTED` (+ `QuotaFailure`, `RetryInfo`) | G18, G21 | existing texts: free tier „Gemini-Gratiskontingent … aufgebraucht“, paid „zu viele Anfragen“ | – |

Shape of the bodies the app parses (fixtures in `GeminiFixtures.kt` → `GeminiErrors`, mock switches in
docs/AI-PROVIDERS.md §14; the 401 shape is OBSERVED [P1], the others follow the documented google.rpc error model and the
forum quotes — UNVERIFIED against a real blocked project):

```json
{"error":{"code":403,"message":"Generative Language API has not been used in project 000000000000 before or it is disabled. …","status":"PERMISSION_DENIED",
 "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED","domain":"googleapis.com",
             "metadata":{"service":"generativelanguage.googleapis.com","consumer":"projects/000000000000","activationUrl":"https://console.developers.google.com/…"}},
            {"@type":"type.googleapis.com/google.rpc.Help","links":[{"description":"Google developers console API activation","url":"https://console.developers.google.com/…"}]}]}}
{"error":{"code":429,"message":"You exceeded your current quota … limit: 0, model: gemini-3.8-flash","status":"RESOURCE_EXHAUSTED",
 "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
              "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaDimensions":{"location":"global","model":"gemini-3.8-flash"},"quotaValue":"0"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"20s"}]}}
```

`ApiErrorBody` reads all of it: `error.code`, `status`, `message`, `ErrorInfo.reason`/`domain`/`metadata`, `Help.links[].url`,
`QuotaFailure.violations[]` (metric, id, value, dimensions) and `RetryInfo.retryDelay` — the message, metadata and links only to
classify, never to show.

### 12.5 Model fallback

When Google refuses the **model** for the key before the stream starts (404, a 400/403 about the model, a quota of 0 that is not
the search's), the chat and the key test try the next model of the chain **`gemini-3.8-flash` → `gemini-3.7-flash` →
`gemini-3.5-flash-lite` → `gemini-3.1-flash-lite`** (a model outside the chain, e.g. 3.1 Pro or a typed id, starts at the top),
each **once**, without retries or waits. None of them needs billing, so the chain works on both tiers.

* Chat: the model that answered is kept **for this key** (`ProviderSettings.modelFallback` = chosen → working, stored only while
  the key that answered is still the stored one); the next requests go there directly. The answer carries the calm note
  „Gemini 3.8 Flash ist für deinen Key nicht freigeschaltet – die App nutzt jetzt Gemini 3.7 Flash.“, and Settings → KI shows the
  same line under the model picker (the picker keeps marking the chosen model).
* The fallback is dropped when a key is saved, the tariff changes or another model is chosen — the chosen model is tried again.
* Key test: the model list (§13) tells which fallbacks the key can see; the chosen model is always tried first (aliases and
  typed ids may be missing from the list).
* When no model answers, the chosen model's refusal is reported (`ModelUnavailable`, with its diagnostic).

### 12.6 Diagnostic line ("Details")

Every failed chat answer and every failed key test carries one key-free technical line that the user can open under
„Details“, select and copy: `HTTP <code> · <status> · <ErrorInfo.reason…> · <QuotaFailure.quotaId> · limit <n> · retry <s>s`
(stream errors: `Stream · <code> · <status>`; connection problems: `Verbindung · <ExceptionClass>`), e.g.
`HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED`. Only identifier-like fields go in (pattern
`[A-Za-z][A-Za-z0-9_.-]{0,63}`), never a message, metadata (project numbers, activation URLs), a help link or any URL; where the
key is at hand the line is dropped if it should ever contain it (`ErrorDiagnostics.redact`). Stored with the chat history.

---

## 13. Key format and key test

* **Format: not documented** on the official key page [G22]. The page (LU 2026-10-06, re-read 2026-10-09) says: standard keys
  "don't identify a caller"; **auth keys** are "bound directly to a Google Cloud service account", restricted to the Gemini API
  by default, with fast-acting leaked-key enforcement; "Starting May 28, 2026, all new API keys created in Google AI Studio are
  automatically created as auth keys"; creating one needs the IAM permissions `iam.serviceAccounts.create` and
  `iam.serviceAccountApiKeyBindings.create`; **unrestricted standard keys are rejected** (restricted ones still work); dormant
  unrestricted keys are blocked since 2026-05-07 ("Blocked" tag in AI Studio); a Cloud Console key without the Generative
  Language API in its API restrictions fails [G22]. Forum posts (F1, unofficial) say new keys start with **`AQ.`**, legacy keys
  with **`AIza`**; P1 shows the server treats `AQ.` strings like OAuth tokens.
* **Header/endpoint for auth keys: no difference documented.** G22's REST sample sends the auth key in **`x-goog-api-key`** to
  the same host (`generativelanguage.googleapis.com/v1beta/…`); no `Authorization: Bearer`, no other endpoint. The forum user who
  tried `Authorization: Bearer` got 401 `API_KEY_SERVICE_BLOCKED` [F1] → keep `x-goog-api-key` (the mock server warns about an
  `Authorization` header on Gemini routes).
* → **Validate loosely**: the app cleans a pasted key (spaces, line breaks, quotes around it, invisible characters such as a
  zero-width space or a BOM anywhere — `cleanApiKey`) and refuses only inner spaces or non-ASCII characters; never a prefix or a
  length check (`AIza…` and `AQ.…` of any length).
* **Key test (v1.3.1), two steps:**
  1. `GET /v1beta/models?pageSize=1000` (no tokens; 1000 is the documented page maximum [G8]) proves the key and lists the
     models it can see (`models[].name`, `supportedGenerationMethods` contains `generateContent`). A refusal of the key or the
     project ends the test here with its cause (§12.2, §12.4).
  2. `POST /v1beta/models/{model}:generateContent` with
     `{"contents":[{"role":"user","parts":[{"text":"Antworte nur: OK"}]}],"generationConfig":{"thinkingConfig":{"thinkingLevel":"low"},"maxOutputTokens":32}}`
     (no tools, a few tokens, recorded like a request; 0 $ on the free tier). This is what makes the test fail with the real cause
     (blocked generation, free tier not in the country, model not open) instead of "OK". A refused model → the listed fallbacks
     (§12.5). 429 → the key works, stored with „Kontingent gerade aufgebraucht“; 402 → stored, prepaid credit used up.
  Search entitlement is **not** tested (one query ≈ $0.014); the chat's safety net handles it (docs/AI-PROVIDERS.md §7.2).
* Rate limits/usage are per **project**, not per key [G21].

---

## 14. Rate limits [G21, G23]

* Dimensions: RPM, TPM (input), RPD; some models TPD/IPM. **Per project.** RPD resets at **midnight Pacific**. Preview models are more restricted.
* Actual numbers are **only shown in AI Studio** (https://aistudio.google.com/rate-limit); the docs no longer list them.
* Tiers: Free (active project) → Tier 1 (billing linked; billing cap $250) → Tier 2 ($100 paid + 3 days; $2,000) → Tier 3 ($1,000 + 30 days; $20k–100k+).
* **Spend-based limits** per rolling 10 min: Tier 1 $10, Tier 2 $50, Tier 3 $200 → 429 `RESOURCE_EXHAUSTED`.
* Paid tier setup = link billing + **prepay min. $5**; credits expire after 12 months; at $0 balance all keys fail with **402** [G23].
* Free tier: "Content used to improve our products"; paid: not [G3].

---

## 15. Gotchas

1. **generateContent is "legacy"** in Google's wording but fully supported; default docs pages now show only the Interactions API. The generateContent guides live under `/gemini-api/docs/generate-content/…` [G25].
2. **Invalid key ≠ 401** for classic keys: 400 `API_KEY_INVALID`. `AQ.` keys: 401. Map both [G18, P1].
3. **Error before stream has `Content-Type: text/event-stream`** but a JSON body → always branch on HTTP status first [P1].
4. **Free tier: no Google Search on Gemini 3.x.** Chat without search works on free keys; Live News needs a paid project [G3].
5. **`groundingChunks` are incremental while streaming**; indices are global → append in order [G6].
6. **Segment offsets are bytes**, not chars [G6].
7. **`web.title` is a domain and `web.uri` a vertexaisearch redirect** [G10] – no article titles from grounding; don't resolve/crawl the links (Terms) [G24].
8. **Search Suggestions must be displayed** (HTML/CSS → WebView, unmodified) [G10, G24, G35].
9. **Don't send `temperature`/`topP`/`topK`/`candidateCount`** (deprecated/unsupported on Gemini 3.x; future 400) [G5].
10. **No prefilled model turn** (last turn `model` → 400) [G5].
11. **`thinkingLevel: minimal` errors on 3.8/3.7 Flash and 3.1 Pro**; 3.5/3.1 Flash-Lite default to minimal (raise for Live) [G2, G4, G5].
12. **Thought signatures**: mandatory for function-calling turns (400), may arrive in a final empty-text part, keep part boundaries [G16, G17].
13. **FunctionResponse needs `id` + `name`, one per call**; mismatch on generateContent = silent empty `STOP` answer [G5].
14. Tool combination: `includeServerSideToolInvocations: true` + `VALIDATED`; return **all** parts incl. `toolCall`/`toolResponse`; `functionCall` not necessarily last [G13, G12].
15. Structured output + tools only on **3.1 Pro Preview and 3.8 Flash** (generateContent docs) – the Interactions doc just says "Gemini 3" [G15].
16. `parameters` (OpenAPI subset) has **no `additionalProperties`** → use `parametersJsonSchema` [G6].
17. `responseSchema` is deprecated → `generationConfig.responseFormat.text.{mimeType, schema}` [G6].
18. **Prices change 2027-01-01** for 3.8/3.7/3.6 Flash (2×) → `AiPricing` must be date-aware [G3, G4].
19. `gemini-3.1-flash-lite` has an earliest shutdown of 2027-05-07; 2.5 models are restricted to previous users [G30].
20. Implicit cache needs ≥ 4,096 prompt tokens on 3.x → put article/briefing context first and keep it byte-stable [G32].
21. Interactions: `store` defaults to **true** (55 days retention paid) → send `false`; no time-range filter; no custom safety settings; docs inconsistent on event names/paths [G25, G26, G27, G28, G29].
22. OpenAI-compatible endpoint (`/v1beta/openai/chat/completions`, `Authorization: Bearer`) lists Google Search only for image generation via `extra_body` → **not usable** for our search features [G34].
23. Use the header, not `?key=` (API reference samples still show `?key=`) [G6, G9].
24. SSE readers must tolerate long silences (thinking + search before the first token): OkHttp `readTimeout` 0 or ≥ 120 s, plus a user "Abbrechen".
25. `MALFORMED_FUNCTION_CALL` when the prompt demands structured text right before a tool call → don't ask for pre-tool text [G12].
26. **New AI Studio keys (`AQ.…`, auth keys since 2026-05-28) can be rejected with 401 `ACCESS_TOKEN_TYPE_UNSUPPORTED` although AI
    Studio lists them as valid** [G22, F1]; no official fix as of 2026-10-09. Send them exactly like `AIza…` keys (`x-goog-api-key`),
    never as `Authorization: Bearer` (that got `API_KEY_SERVICE_BLOCKED` in F1), and explain the error instead of "Key ungültig" (§12.4).
27. **Branch on `ErrorInfo.reason`, not only on the HTTP status**: `API_KEY_SERVICE_BLOCKED` came as 403 and 401; `SERVICE_DISABLED`
    is a 403 like "no key" (§12.4).
28. **A 429 with a quota limit of 0 is no rate limit**: the free tier does not offer that model (or the search) to the project — retrying
    never helps, another model does (§12.5).
29. **The model list proves the key, not the generation**: a key can list models and still be refused on `generateContent`; the key
    test therefore generates a few tokens (§13).

---

## 16. Open questions (not answerable from the docs on 2026-10-09)

1. Exact HTTP error when a **free-tier** key sends `googleSearch` to a Gemini 3.x model.
2. Is `groundingMetadata` (chunks/supports) returned when `includeServerSideToolInvocations` is on, and do `toolResponse` parts ever contain result URLs (docs show only `search_suggestions`)?
3. Is `toolUsePromptTokenCount` billed (at input rate)? Do search results count in `promptTokenCount` without tool combination?
4. Semantics of `GoogleSearch.timeRangeFilter` (publication time? index time?) and whether it is honored on the Gemini Developer API.
5. SSE line framing and **mid-stream error** format for successful `streamGenerateContent?alt=sse` streams; whether intermediate chunks carry partial `usageMetadata`.
6. How to tell RPM vs RPD vs spend-limit 429s apart on generateContent (no documented `ErrorInfo.reason`/`QuotaFailure`).
7. Can `functionCallingConfig.mode: ANY` be combined with built-in tools (forcing `publish_articles` while still searching)?
8. `AQ.` auth keys with `x-goog-api-key` on REST: official docs silent on the format; forum users report 401s (F1) without an official resolution.
   Re-checked 2026-10-09: G22 still documents no other header or endpoint for auth keys; F1 has new posts (2026-10-07/08: a freshly
   created `AQ.` key failed with `ACCESS_TOKEN_TYPE_UNSUPPORTED` in the SDK and with `API_KEY_SERVICE_BLOCKED` as a bearer token) and
   still no answer from Google. Unknown: the exact status of a key blocked as leaked (G20 quotes only the message), and whether
   `API_KEY_SERVICE_BLOCKED` on `x-goog-api-key` is a 401 or a 403 (the app does not depend on it).
9. Interactions: `v1beta` vs `v1beta2` path; `interaction.status_update` vs `interaction.in_progress`/`requires_action`; `gemini-3.5-flash-lite` is listed in the overview but missing from the reference's model enum.
10. Targets of the `gemini-*-latest` aliases.
11. Whether `groundingChunks[].web.uri` redirect links expire, and whether showing the resolved destination URL would violate the Terms.
