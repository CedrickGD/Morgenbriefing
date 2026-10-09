# Provider spec: OpenAI (Responses API)

**AS_OF: 2026-10-09.** Every fact below comes from the official OpenAI developer docs, fetched on
2026-10-09 (the markdown twin of each page: append `.md` to the URL). `platform.openai.com/docs/*`
now answers `301 → developers.openai.com/api/docs/*`. Anything not found in the docs is marked
**UNVERIFIED** or listed under *Open questions*. Nothing here comes from memory.

Target: Morgenbriefing, Android (Kotlin, OkHttp 5, kotlinx-serialization, minSdk 24), plain HTTPS + SSE, no vendor SDK.

---

## 0. Sources (all fetched 2026-10-09)

| # | Page | URL |
|---|---|---|
| S1 | Models overview | https://developers.openai.com/api/docs/models |
| S2 | GPT-6 Astra model page | https://developers.openai.com/api/docs/models/gpt-6-astra |
| S3 | GPT-6.1 Sol model page | https://developers.openai.com/api/docs/models/gpt-6.1-sol |
| S4 | GPT-6 Luna model page | https://developers.openai.com/api/docs/models/gpt-6-luna |
| S5 | GPT-6 Sol / GPT-5.6 Sol, Terra, Luna / GPT-5.5 pages | https://developers.openai.com/api/docs/models/gpt-6-sol, …/gpt-5.6-sol, …/gpt-5.6-terra, …/gpt-5.6-luna, …/gpt-5.5 |
| S6 | Pricing (tokens + built-in tools) | https://developers.openai.com/api/docs/pricing |
| S7 | Web search guide | https://developers.openai.com/api/docs/guides/tools-web-search |
| S8 | Streaming guide | https://developers.openai.com/api/docs/guides/streaming-responses |
| S9 | Responses streaming events reference | https://developers.openai.com/api/reference/resources/responses/streaming-events |
| S10 | Responses create reference | https://developers.openai.com/api/reference/resources/responses/methods/create |
| S11 | Function calling guide | https://developers.openai.com/api/docs/guides/function-calling |
| S12 | Structured outputs guide (strict-schema subset) | https://developers.openai.com/api/docs/guides/structured-outputs |
| S13 | Reasoning guide | https://developers.openai.com/api/docs/guides/reasoning |
| S14 | Using GPT-6 (latest-model guide) | https://developers.openai.com/api/docs/guides/latest-model |
| S15 | Prompt caching guide | https://developers.openai.com/api/docs/guides/prompt-caching |
| S16 | Error codes | https://developers.openai.com/api/docs/guides/error-codes |
| S17 | Rate limits | https://developers.openai.com/api/docs/guides/rate-limits |
| S18 | API reference overview (auth, headers) | https://developers.openai.com/api/reference/overview |
| S19 | Models list / retrieve | https://developers.openai.com/api/reference/resources/models/methods/list |
| S20 | Chat Completions reference + streaming events | https://developers.openai.com/api/reference/resources/chat , https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events |
| S21 | Changelog | https://developers.openai.com/api/docs/changelog |
| S22 | Deprecations | https://developers.openai.com/api/docs/deprecations |
| S23 | Sign in with ChatGPT (ChatGPT plan usage) | https://developers.openai.com/siwc/quickstart , …/siwc/token-sharing-open-source , …/sign-in , …/models-and-inference , …/preview-limitations , …/errors-and-recovery , …/token-reference |
| S24 | Workload identity federation | https://developers.openai.com/api/docs/guides/workload-identity-federation |
| S25 | Token counting | https://developers.openai.com/api/docs/guides/token-counting |

---

## 1. TL;DR for the implementation

* Use **`POST https://api.openai.com/v1/responses`** with `stream: true`. Chat Completions is the legacy path. **GPT-6 Astra and GPT-6.1 Sol do not support tool calling on Chat Completions** [S11, S13, S14], and web search on Chat Completions only works with the special model `gpt-5-search-api` [S7].
* Web search is the built-in tool **`{"type": "web_search"}`** (`web_search_preview` is legacy) [S7, S10]. It runs server-side inside the same response. Citations come back as **`url_citation`** annotations on `output_text` [S7, S9].
* **Live News:** use one request with `tools: [web_search, publish_articles (function)]`. The model searches (server-side), then ends the response with a `function_call` item. There is no separate two-step pattern: the response simply stops at the function call [S11]. If the arguments fail validation, send a follow-up request with `function_call_output` (correction loop).
* Models (§3): **default `gpt-6.1-sol`** ($2 / $10 per 1M), **cheap/fast `gpt-6-luna`** ($0.10 / $0.50), **top `gpt-6-astra`** ($10 / $50). Web search costs **$10 per 1,000 calls**, plus search-content tokens at the model's input rate [S6].
* `usage` reports input, output, cached, cache-write and reasoning tokens. It does **not** report a web-search count or a cost [S9]. Count the `web_search_call` items yourself.
* **Prompt-caching gotcha (new for GPT-5.6+):** cache **writes** cost **1.25×** input. The default `implicit` mode writes a cache entry whenever the prefix is ≥ 1,024 tokens [S15, S10]. See §9.
* **Schema gotcha:** OpenAI strict mode does **not** list `format: "uri"` as supported, and a bare `{"type":"null"}` is not a listed type. The current `publish_articles` schema in `ai/LivePublish.kt` uses both, so it needs an OpenAI variant (§7.4).

---

## 2. Endpoint and auth

| Item | Value | Source |
|---|---|---|
| Base URL | `https://api.openai.com/v1` | S10, S18 |
| Create response | `POST /v1/responses` | S10 |
| Auth header | `Authorization: Bearer <OPENAI_API_KEY>` (an API key or a short-lived workload-identity access token) | S18 |
| Content type | `Content-Type: application/json` | S10 examples |
| Optional | `OpenAI-Organization: <org id>` and `OpenAI-Project: <project id>`. These are needed only for multi-org users or legacy **user** keys | S18 |
| Optional | `X-Client-Request-Id: <uuid>`: ASCII, ≤ 512 chars, unique per request. Lets support look up requests that timed out | S18 |
| Response headers worth logging | `x-request-id`, `openai-processing-ms`, `x-ratelimit-*`, `Retry-After` | S17, S18 |
| Header size | Keep all request headers < 64 KiB in total | S18 |
| Transport | HTTP + SSE (`stream: true`). WebSocket mode also exists (not needed here) | S8 |

The docs warn: "Don't share it with others or expose it in any client-side code such as browsers or apps" [S18]. Here the key belongs to the user and is stored only on their own device (Keystore, as with the Anthropic key). That is a BYOK setup, so the warning is about shipping *your* key. Keep it in mind for the UI copy.

---

## 3. Models and prices (AS_OF 2026-10-09)

### 3.1 Recommended

| Role | Model ID | Context | Max output | Input / 1M | Cached input / 1M | Cache write / 1M | Output / 1M | `reasoning.effort` | Notes |
|---|---|---|---|---|---|---|---|---|---|
| **Top** | `gpt-6-astra` | 1,050,000 (max input 922,000) | 128,000 | $10.00 | $1.00 | $12.50 | $50.00 | `low` `medium` `high` `xhigh` `max`. **No `none`** (returns HTTP 400) | Knowledge cutoff 2026-04-30. No custom `temperature`/`top_p`/`logprobs`. Tool calling only via Responses. Async misalignment monitoring can stop a conversation [S2, S13, S21] |
| **Default** | `gpt-6.1-sol` | 1,050,000 (922,000) | 128,000 | $2.00 | **$0.10** (5 %) | $2.50 | $10.00 | `low` `medium` (default) `high` `xhigh` `max`. **No `none`/`minimal`** | Released 2026-09-29. Tool calling only via Responses. Chat Completions only without tools [S3, S14, S21] |
| **Cheap / fast** | `gpt-6-luna` | 1,050,000 (922,000) | 128,000 | $0.10 | $0.01 | $0.125 | $0.50 | `none` `low` `medium` (default) `high` `xhigh` `max` | Knowledge cutoff 2026-05-18. Released 2026-09-22. Chat Completions supports function calling only with `reasoning_effort: "none"` [S4, S14, S21] |

All three list the Responses tools `web_search`, `function_calling`, `streaming`, `structured_outputs` and `prompt_caching` as supported [S2, S3, S4].

**Long context:** prompts with **more than 272K input tokens** are billed at 2× input and cache rates and 1.5× output, **for the whole request** [S2–S4, S6].
**Other tiers** [S6]: Batch and Flex cost 50 % of Standard. Fast mode (formerly "Priority", renamed 2026-07-30) costs 2× and is selected with `service_tier: "fast"` or `"priority"`. Ultrafast costs 6× (the pricing table lists it only for `gpt-6-astra` and `gpt-6.1-sol`). Regional processing (data residency) adds 10 %.

### 3.2 Alternatives (Standard, per 1M tokens, ≤ 272K) [S5, S6]

| Model ID | Input | Cached | Cache write | Output | Notes |
|---|---|---|---|---|---|
| `gpt-6-sol` | 2.00 | 0.20 | 2.50 | 10.00 | Predecessor of 6.1 Sol. Supports `none` |
| `gpt-5.6-sol` | 4.00 | 0.40 | 5.00 | 20.00 | Promo price "at least through November 21, 2026". The alias `gpt-5.6` routes here |
| `gpt-5.6-terra` | 2.00 | 0.20 | 2.50 | 12.00 | The former "mini" tier |
| `gpt-5.6-luna` | 0.20 | 0.02 | 0.25 | 1.20 | The former "nano" tier |
| `gpt-5.5` | 5.00 | 0.50 | – | 30.00 | Snapshot `gpt-5.5-2026-04-23`. The web-search guide still names it as its reference model |
| `gpt-5-search-api` | 1.25 | 0.125 | – | 10.00 | **Chat Completions only.** Always searches. 200k context [S6, S7] |

There are **no nano/mini IDs in the GPT-6 family**. "Luna" is the low-cost tier [S1]. `gpt-5.4-nano` shuts down on 2027-04-01, and the `gpt-5`/`gpt-5-mini`/`gpt-5-nano` 2025-08-07 snapshots shut down on 2026-12-11 [S22].

### 3.3 Built-in tool prices [S6]

| Tool | Price |
|---|---|
| **`web_search` (all models)** | **$10.00 / 1k calls** + search content tokens billed at model rates |
| `web_search_preview`, reasoning models | $10.00 / 1k calls + search content tokens at model rates |
| `web_search_preview`, non-reasoning models | $25.00 / 1k calls, search content tokens free |
| Image web search | $10.00 / 1k calls + content tokens |

"Search actions incur a tool call cost" [S7]. The billable unit is the `web_search_call` whose `action.type == "search"`. The docs do not say whether `open_page` and `find_in_page` actions are billed (see *Open questions*).

---

## 4. Request JSON: streaming chat ("Frag nach")

```http
POST https://api.openai.com/v1/responses
Authorization: Bearer sk-...
Content-Type: application/json
```

```json
{
  "model": "gpt-6.1-sol",
  "stream": true,
  "store": false,
  "reasoning": { "effort": "low" },
  "text": { "verbosity": "medium" },
  "max_output_tokens": 8000,
  "instructions": "Du bist der Nachrichten-Assistent von Morgenbriefing. Antworte immer auf Deutsch, knapp und sachlich. Nutze die Websuche nur für Hintergrund, der nicht im Kontext steht, und nenne dann die Quellen.",
  "input": [
    {
      "role": "developer",
      "content": [
        { "type": "input_text", "text": "KONTEXT (Artikel):\nTitel: ...\nQuelle: ...\n\n<Artikeltext / Briefing>" }
      ]
    },
    { "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" },
    { "role": "assistant", "content": "…frühere Antwort…", "phase": "final_answer" },
    { "role": "user", "content": "Und seit wann gilt das?" }
  ],
  "tools": [
    {
      "type": "web_search",
      "search_context_size": "medium",
      "user_location": { "type": "approximate", "country": "DE", "city": "Berlin", "region": "Berlin", "timezone": "Europe/Berlin" }
    }
  ],
  "tool_choice": "auto",
  "max_tool_calls": 3,
  "include": ["web_search_call.action.sources"]
}
```

Field notes:

* `instructions` is "a system (or developer) message inserted into the model's context". You can also put `{"role":"developer"|"system", ...}` messages in `input` [S10]. The article context goes into a **developer message**, which lets prompt caching place a breakpoint after it (top-level `instructions` cannot carry an explicit breakpoint) [S15].
* `store` defaults to **true**: responses are stored for ≥ 30 days. Use `store: false` for privacy. Multi-turn then means **replaying history in `input`**; `previous_response_id` needs stored responses [S10, S13]. With `store: false`, reasoning items come back with `encrypted_content` automatically [S13].
* Assistant turns can be replayed as `{"role":"assistant","content":"…"}`. For newer models, "preserve and resend `phase` on all assistant messages — dropping it can degrade performance" [S10 schema].
* `max_output_tokens` caps **visible output plus reasoning tokens**. If it is hit, the response ends `incomplete` with `reason: "max_output_tokens"`, possibly before any visible text. OpenAI suggests reserving ≥ 25,000 tokens while experimenting [S13].
* `max_tool_calls` caps the **total built-in tool calls** (web search) per response [S10]. That makes it the cost guard for searches.
* With `reasoning.effort` set to anything other than `none`, remove `temperature`, `top_p` and `top_logprobs` [S14].
* `text.verbosity`: `low` | `medium` | `high` [S10].
* `user_location` defaults to **the United States** when omitted. Send `{"type":"approximate"}` to avoid that, or set DE fields [S10].
* `stream_options.include_obfuscation` (default true) adds an `obfuscation` padding field to delta events. Set it to `false` to save bandwidth [S10].

---

## 5. SSE format (Responses API)

### 5.1 Framing

Each event arrives as a standard SSE pair. The `type` inside `data` repeats the event name, so **parse `data` and switch on its `type`** [S10 streaming example, S9]:

```
event: response.output_text.delta
data: {"type":"response.output_text.delta","item_id":"msg_…","output_index":0,"content_index":0,"delta":"Hi","sequence_number":4,"logprobs":[]}

```

* Every event carries a monotonically increasing `sequence_number` [S9].
* **No `data: [DONE]` sentinel is documented for Responses.** The stream ends after a terminal event: `response.completed`, `response.incomplete`, `response.failed` or `error`. Chat Completions does end with `data: [DONE]` [S20]. Tolerate `[DONE]` defensively.
* Lines starting with `:` are SSE comments. Ignore them.
* Adding new event types is a backwards-compatible change for OpenAI [S18]. **Unknown event types must be ignored**, and the parser should use `ignoreUnknownKeys = true`.
* An HTTP error (4xx/5xx JSON) arrives **before** the stream starts. "An error after streaming begins can arrive as a stream event; don't automatically replay a request after consuming output" [S17].

### 5.2 Events to handle (exact names and fields) [S9, S11]

| Event `type` | Key fields | Use |
|---|---|---|
| `response.created` / `response.in_progress` / `response.queued` | `response{id,status,model,…}` | Keep `response.id`. Ignore the rest |
| `response.output_item.added` | `output_index`, `item{type,id,…}` | `item.type`: `"message"` (has `role`, `phase`), `"function_call"` (has `name`, `call_id`, `arguments:""`), `"web_search_call"`, `"reasoning"`. Map `item.id` and `output_index` to the item kind |
| `response.content_part.added` / `.done` | `item_id`, `content_index`, `part{type:"output_text",text,annotations}` | Optional |
| **`response.output_text.delta`** | `item_id`, `output_index`, `content_index`, **`delta`** | **Text chunk** to append to the UI |
| `response.output_text.done` | `text` (full text of that part) | Authoritative final text |
| **`response.output_text.annotation.added`** | `item_id`, `content_index`, `annotation_index`, **`annotation`** | When `annotation.type == "url_citation"`, read **`url`**, **`title`**, `start_index`, `end_index` |
| `response.refusal.delta` / `.done` | `delta` / `refusal` | Model refusal text. Show it as an answer |
| `response.web_search_call.in_progress` / `.searching` / `.completed` | `item_id`, `output_index` | UI status "Suche läuft…". These events carry **no** query or results |
| **`response.function_call_arguments.delta`** | `item_id`, `output_index`, **`delta`** | Buffer the arguments JSON per `item_id` |
| **`response.function_call_arguments.done`** | `item_id`, `output_index`, **`arguments`** | Final JSON string. The reference schema has **no `name`**, so take `name` and `call_id` from `output_item.added` |
| **`response.output_item.done`** | `output_index`, `item` (complete) | Final item. For `web_search_call`: `status`, `action{type:"search", query?, queries?, sources?[{type:"url",url}]}` / `{type:"open_page",url}` / `{type:"find_in_page",pattern,url}`. For `message`: `content[].annotations[]` (authoritative citation list). For `function_call`: `name`, `call_id`, `arguments`. For `reasoning`: `encrypted_content` (keep it for replay) |
| `response.reasoning_summary_text.delta` etc. | `delta` | Only when `reasoning.summary` is set. Ignore |
| **`response.completed`** | `response{status:"completed", output[], usage{…}}` | Terminal success. Read **`usage`** here |
| **`response.incomplete`** | `response.incomplete_details.reason` ∈ `max_output_tokens`, `max_messages`, `content_filter`, `steered` | Terminal. Show partial text plus a hint |
| **`response.failed`** | `response.error{code,message}` | Terminal error (codes in §11) |
| **`error`** | `code` (string or null), `message`, `param`, `sequence_number` | Terminal mid-stream error |

`response.status` ∈ `completed`, `failed`, `in_progress`, `cancelled`, `queued`, `incomplete` [S9].

Documented function-call stream (verbatim shape from S11):

```
{"type":"response.output_item.added","response_id":"resp_1234xyz","output_index":0,"item":{"type":"function_call","id":"fc_1234xyz","call_id":"call_1234xyz","name":"get_weather","arguments":""}}
{"type":"response.function_call_arguments.delta","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":0,"delta":"{\""}
…
{"type":"response.function_call_arguments.done","response_id":"resp_1234xyz","item_id":"fc_1234xyz","output_index":0,"arguments":"{\"location\":\"Paris, France\"}"}
{"type":"response.output_item.done","response_id":"resp_1234xyz","output_index":0,"item":{"type":"function_call","id":"fc_1234xyz","call_id":"call_1234xyz","name":"get_weather","arguments":"{\"location\":\"Paris, France\"}"}}
```

Typical order for a chat answer with a search (illustrative, assembled from documented events, **not a captured trace**): `response.created` → `output_item.added(reasoning)` … `output_item.done(reasoning)` → `output_item.added(web_search_call)` → `web_search_call.in_progress` → `.searching` → `.completed` → `output_item.done(web_search_call, action…)` → (more searches) → `output_item.added(message)` → `content_part.added` → `output_text.delta`* interleaved with `output_text.annotation.added`* → `output_text.done` → `content_part.done` → `output_item.done(message)` → `response.completed`.

### 5.3 Parser state machine (Kotlin sketch)

```
items: Map<itemId, Kind{MESSAGE(phase), FUNCTION(name, callId, argsBuf), WEB_SEARCH, REASONING, OTHER}>
on output_item.added   -> register item; message.phase=="commentary" -> route deltas to a status line, not the answer
on output_text.delta   -> answer.append(delta) if item is a non-commentary message
on output_text.annotation.added (url_citation) -> citations.add(title,url) (dedupe by normalized url)
on function_call_arguments.delta/done -> args per item_id
on output_item.done    -> web_search_call: searchCount += (action?.type=="search" || action==null) ? 1 : 0; collect action.sources urls
                          message: replace citations with item.content[*].annotations (authoritative)
                          reasoning/function_call/web_search_call: keep raw JSON item for replay (store=false)
on response.completed  -> usage; done
on response.incomplete -> reason; done (partial)
on response.failed / error -> map error (§11); done
unknown type           -> ignore
```

---

## 6. Web search tool

### 6.1 Definition [S7, S10]

```json
{
  "type": "web_search",
  "search_context_size": "medium",
  "user_location": { "type": "approximate", "country": "DE", "city": "Berlin", "region": "Berlin", "timezone": "Europe/Berlin" },
  "filters": { "allowed_domains": ["tagesschau.de", "spiegel.de"] },
  "external_web_access": true
}
```

| Parameter | Values | Notes |
|---|---|---|
| `type` | `"web_search"` or the pinned `"web_search_2025_08_26"` | `web_search_preview` is legacy and lacks `filters`, `external_web_access` and `return_token_budget` |
| `search_context_size` | `low` / `medium` (default) / `high` | Guidance only, not an exact token count |
| `user_location` | `{type:"approximate", country (ISO-2), city, region, timezone (IANA)}` | **Defaults to the US** if omitted |
| `filters.allowed_domains` | ≤ 100 domains, no scheme, subdomains included | Responses + `web_search` only. The guide also documents `filters.blocked_domains` (≤ 100), but the API-reference schema lists only `allowed_domains` |
| `external_web_access` | bool, default `true` | `false` means cache/offline-only search |
| `return_token_budget` | `"default"` / `"unlimited"` | GPT-5+ reasoning search only. Not needed |
| `search_content_types`, `image_settings` | image search | Not needed |

* Forcing a search: `tool_choice: "required"` (some tool must be called) or `allowed_tools`. The `ToolChoiceTypes` enum lists `web_search_preview` but **not** `web_search` [S10].
* Search context is capped at **128k** even when the model's window is larger [S7].
* Web search rate limits are the model's tiered rate limits [S7].
* There is no date/recency parameter. **Recency comes only from the prompt**: give the current time and a window such as "letzte 6 Stunden".

### 6.2 How citations come back [S7, S9]

* The `message` item's `content[0].annotations[]` holds `{"type":"url_citation","start_index":…,"end_index":…,"url":"…","title":"…"}`. While streaming, the same objects arrive as `response.output_text.annotation.added`.
* `include: ["web_search_call.action.sources"]` returns **every URL consulted** in `web_search_call.action.sources[] = {type:"url", url}`. These have **no titles**, and the list is usually longer than the citation list. Real-time feeds are labelled `oai-sports`, `oai-weather` and `oai-finance`.
* Doc examples show citation URLs carrying `?utm_source=chatgpt.com`. Normalize before deduping.
* Display rule from OpenAI: "inline citations must be made clearly visible and clickable in your user interface" [S7].
* Model notes [S7]: web search does not support `gpt-5` with `minimal` effort, and `gpt-5.4` with `none` "may produce lower-quality results". For GPT-6 there is no statement, so use **`low` or higher** with search (`gpt-6-astra` and `gpt-6.1-sol` have no `none` anyway).

---

## 7. Function calling

### 7.1 Definition (Responses: flat, no `function` wrapper) [S11, S10]

```json
{
  "type": "function",
  "name": "publish_articles",
  "description": "Veröffentlicht die fertig recherchierten Live-Artikel … genau einmal …",
  "strict": true,
  "parameters": {
    "type": "object",
    "properties": { "articles": { "type": "array", "items": { "$ref-or-inline": "article schema, see 7.4" } } },
    "required": ["articles"],
    "additionalProperties": false
  }
}
```

### 7.2 The call (output item) [S11]

```json
{ "type": "function_call", "id": "fc_…", "call_id": "call_…", "name": "publish_articles", "arguments": "{\"articles\":[…]}", "status": "completed" }
```

`arguments` is a **JSON string**. Assume 0..n calls per response [S11].

### 7.3 The result (input item in the next request) [S11]

```json
{ "type": "function_call_output", "call_id": "call_…", "output": "ok" }
```

`output` is usually a string (any format), or an array of text/image/file parts. For reasoning models, **pass back the reasoning items with the tool output**: "all items between the last user message and your function call output … untouched" [S13]. With `store: false`, replay the `reasoning` items (with `encrypted_content`), the `web_search_call` items and the `function_call` items exactly as received [S13]. All of them are valid `input` item types [S10].

### 7.4 Strict mode rules, and what to change in our schema [S11, S12]

* With `strict: true`, every object needs `additionalProperties: false` and **all** properties in `required`. Optional fields are written as `"type": ["string","null"]`.
* If `strict` is omitted, Responses tries to normalize the schema to strict and silently falls back to non-strict (the tool then shows `strict: false`). An invalid schema sent with `strict: true` is **rejected** [S11].
* Supported string `format`s: `date-time`, `time`, `date`, `duration`, `email`, `hostname`, `ipv4`, `ipv6`, `uuid`. **`uri` is not listed** [S12]. Supported types: string, number, boolean, integer, object, array, enum, anyOf. The root must be an object, not `anyOf`. Limits: ≤ 5,000 properties, ≤ 10 nesting levels, ≤ 1,000 enum values. Unsupported: `allOf`, `not`, `if/then/else`, `dependent*` [S12].
* → For OpenAI, `LivePublish.articleSchema` must **drop `"format": "uri"`** on `url` and `sources[].url` (keep the URL check client-side) and **replace `"image": {"type":"null"}`** with `{"type":["string","null"]}` or remove the field. A bare `null` type is not in the supported list. **UNVERIFIED** whether either variant is rejected; test once against the API.
* `tool_choice`: `"auto"` (default) | `"required"` | `"none"` | `{"type":"function","name":"publish_articles"}` | `{"type":"allowed_tools","mode":"auto"|"required","tools":[…]}` [S11, S10].
* `parallel_tool_calls: false` "ensures exactly zero or one tool is called" [S11]. Its interaction with built-in web search is undocumented, so **keep the default** and merge multiple `publish_articles` calls.
* "Built-in tools cannot be included in a parallel function-call batch" [S11].

---

## 8. Live News: web search + function in one request

**Rule:** built-in tools (`web_search`) and function tools may sit in the same `tools` array. The model can search several times server-side within the response. When it decides to call `publish_articles`, the response **ends** with the `function_call` item, and your app executes it [S7, S11]. This is the documented tool-calling loop: request → tool call → execute → optional follow-up with the output [S11].

```json
{
  "model": "gpt-6.1-sol",
  "stream": true,
  "store": false,
  "reasoning": { "effort": "medium" },
  "max_output_tokens": 30000,
  "instructions": "Du bist Live-Redakteur von Morgenbriefing. Recherchiere mit der Websuche nur Meldungen aus dem angegebenen Zeitfenster, prüfe sie gegen mindestens eine zweite Quelle und rufe am Ende GENAU EINMAL publish_articles auf – mit allen Artikeln oder mit einer leeren Liste. Schreibe auf Deutsch. Gib keinen Fließtext außerhalb des Werkzeugaufrufs aus.",
  "input": [
    { "role": "user", "content": "Jetzt: 2026-10-09T07:30:00+02:00 (Europe/Berlin). Zeitfenster: letzte 6 Stunden. Rubriken: [{\"id\":\"politik\",\"name\":\"Politik\"},{\"id\":\"tech\",\"name\":\"Technik\"}]. Bereits vorhanden (nicht wiederholen): […]." }
  ],
  "tools": [
    { "type": "web_search", "search_context_size": "medium",
      "user_location": { "type": "approximate", "country": "DE", "timezone": "Europe/Berlin" } },
    { "type": "function", "name": "publish_articles", "description": "…", "strict": true,
      "parameters": { "type": "object", "properties": { "articles": { "type": "array", "items": { "…": "OpenAI variant of LivePublish.articleSchema" } } },
                      "required": ["articles"], "additionalProperties": false } }
  ],
  "tool_choice": "auto",
  "max_tool_calls": 8,
  "include": ["web_search_call.action.sources"]
}
```

Flow:

1. Stream it. Show "Suche…" on `web_search_call` events. Accumulate `publish_articles` arguments.
2. Possible endings: `response.completed` whose last output is a `function_call`, which is normal. Or a `message` with no call: treat that as 0 articles, or retry once with stronger wording.
3. Validate (`LivePublish.validate`). **Cross-check source URLs** against the URLs from `web_search_call.action.sources` and citations; function arguments carry no annotations.
4. If there are problems, send a **second request** with `input = original input + every output item of response 1 (reasoning with encrypted_content, web_search_call, function_call) + {"type":"function_call_output","call_id":…,"output": LivePublish.replyText(outcome)}` and the same `tools`. Repeat with a small cap. If the result is "ok", no follow-up request is needed; optionally send `"ok"` only if a closing message is wanted.
5. `tool_choice: "required"` would force at least one tool call (satisfied by a search too). Do **not** use `"required"` in correction follow-ups, because the model must be free to answer without another call.

Cost guard: `max_tool_calls` (built-in calls only) plus the app's monthly limit.

---

## 9. Usage, cost and caching

`response.usage` (in `response.completed`, also on `incomplete`/`failed` if present) [S9]:

```json
"usage": {
  "input_tokens": 81,
  "input_tokens_details": { "cached_tokens": 0, "cache_write_tokens": 0 },
  "output_tokens": 1035,
  "output_tokens_details": { "reasoning_tokens": 832 },
  "total_tokens": 1116
}
```

* `reasoning_tokens` are **part of `output_tokens`** and are billed as output [S13].
* Cache-write pricing "is not an additive fee: input tokens use the uncached-input, cached-input, or cache-write rate" [S15]. Hence
  `cost = (input − cached − cache_write)·in + cached·cachedRate + cache_write·writeRate + output·out + searchCalls·$0.01` (×2/×1.5 if input > 272K).
* **No web-search count and no cost field** in `usage` [S9]. `searchCalls` = number of `web_search_call` items with `action.type == "search"` (if `action` is missing, count the item). Whether search-content tokens are included in `input_tokens` is **not stated** (see open questions).
* Prompt caching for GPT-5.6+ [S15, S10]:
  * `prompt_cache_options.mode` defaults to `"implicit"`: one breakpoint at the end of the latest eligible message (user message, last tool output, or the last developer message of the initial block). The minimum cacheable prefix is **1,024** visible tokens. TTL is `"30m"`, the only value. Reads cost 0.1× input (**0.05× on `gpt-6.1-sol`**). **Writes cost 1.25×.**
  * Consequence: a one-shot Live run with a long prompt pays +25 % on the written prefix and never reads it back. Options: `prompt_cache_options: {"mode":"explicit"}` with **no** breakpoints, which disables caching and its writes. Or place an explicit `prompt_cache_breakpoint: {"mode":"explicit"}` only after the stable instructions block. For "Frag nach" follow-ups within 30 min, implicit caching pays off.
  * `prompt_cache_key` is optional on GPT-5.6+ (accounting only). `prompt_cache_retention` is deprecated in favour of `prompt_cache_options.ttl`.

---

## 10. Key format and key test

* **Format: not documented in the official docs.** Community and third-party sources only (UNVERIFIED; e.g. https://community.openai.com/t/how-to-create-an-api-secret-key-with-prefix-sk-only-always-creates-sk-proj-keys/1263531 and https://community.openai.com/t/project-api-key-length-has-it-changed-from-48-to-156/920777, seen 2026-10-09): project keys start with `sk-proj-`; also seen are `sk-svcacct-` (service account), `sk-None-` (user key) and legacy `sk-`. Lengths have grown (≈ 160+ chars) and may contain `_` and `-`. **Validate loosely**: non-empty, no whitespace, starts with `sk-`. Do not check length or charset.
* Keys can now carry **expiration dates** (2026-09-10), and orgs can restrict key creation [S21]. An expired key gives 401.
* **Cheapest key test:** `GET https://api.openai.com/v1/models` with `Authorization: Bearer <key>`. It consumes no tokens: a list call; pricing lists only token/tool usage [S19, S6]. 200 means the key is valid; 401 means a bad key. To verify access to the chosen model: `GET /v1/models/{model}` (https://developers.openai.com/api/reference/resources/models/methods/retrieve, fetched 2026-10-09). Each `data[]` entry has `id`, `created`, `owned_by`, `shutdown_date`.
* The models list does **not** prove credit. For a billing check, a minimal `POST /v1/responses` with `gpt-6-luna`, `reasoning.effort:"none"`, `input:"Antworte nur: OK"`, small `max_output_tokens` costs a fraction of a cent. A 429 `credit_balance_exhausted` there means there is no credit. (The minimum allowed `max_output_tokens` is not documented here: UNVERIFIED.)

---

## 11. Error mapping

HTTP errors carry an `error` object with `message`, `type`, `code` and `param`. The error guide refers to `error.code`, `error.type` and `error.param` [S16]. **Decide by status plus `error.code`** [S16, S17]:

| HTTP | `error.type` / `error.code` | Meaning | App reaction |
|---|---|---|---|
| 400 | `invalid_request_error` (`param` names the field) | Bad request: schema, unsupported param (e.g. `temperature` with reasoning, `none` effort on Astra/6.1 Sol), invalid `service_tier` | Do not retry. Log `message`/`param` |
| 401 | – | Invalid/revoked/expired key, wrong org/project, IP not allowlisted | "API-Key ungültig" → settings |
| 403 | – | Country/region/territory not supported | Show the message. Do not retry |
| 429 | code **`credit_balance_exhausted`** (type may still be `insufficient_quota`) | No prepaid credit | "Guthaben aufgebraucht". **Do not retry** |
| 429 | `organization_spend_limit_exceeded` / `project_spend_limit_exceeded` / `organization_usage_limit_exceeded` | Spend or usage limit hit | Do not retry. Explain |
| 429 | type `rate_limit_error`, code **`slow_down`** | Traffic ramped too fast (even within RPM/TPM) | Wait `Retry-After`, then back off |
| 429 | (rate limit reached for requests/tokens) | RPM/TPM exceeded | Wait `Retry-After` or use exponential backoff with jitter, capped |
| 500 | – | Server error | Retry with backoff (bounded) |
| 503 | type `service_unavailable_error`, code **`server_is_overloaded`** | **Model overloaded** | Wait `Retry-After`, retry bounded |

Since 2026-09-02, traffic spikes give `429 slow_down` and overload gives `503 server_is_overloaded` (previously both were 503 `slow_down`) [S17, S21]. Never auto-retry billing or quota errors [S16].

**Mid-stream** (after HTTP 200):
* `response.failed` → `response.error.code` ∈ `server_error`, `rate_limit_exceeded`, `invalid_prompt`, `data_residency_mismatch`, `bio_policy`, `misalignment_policy_violation`, `vector_store_timeout`, image-related codes … (21 values in total) [S9].
* `error` event → `code` (free string or null), `message`, `param` [S9].
* `response.incomplete` → `incomplete_details.reason` (`max_output_tokens`, `content_filter`, …).
* Do not replay automatically after output has been shown [S17].

---

## 12. Rate limits [S17, S2–S4]

Usage tiers simplified on 2026-10-06 to Free / **Build** ($5 purchased, $500/month usage limit) / **Launch** ($100, $5,000/month) / **Grow** ($500, $200,000/month). Free has a $100/month usage limit.

| Tier | Astra / Sol / Terra RPM / TPM | Luna RPM / TPM |
|---|---|---|
| Build | 5,000 / 1,000,000 | 5,000 / 2,000,000 |
| Launch | 10,000 / 4,000,000 | 10,000 / 10,000,000 |
| Grow | 15,000 / 40,000,000 | 30,000 / 180,000,000 |

Headers: `x-ratelimit-limit-requests|tokens`, `x-ratelimit-remaining-requests|tokens`, `x-ratelimit-reset-requests|tokens` (e.g. `1s`, `6m0s`), optional `x-ratelimit-*-project-tokens`, and `Retry-After` (seconds) on temporary 429/503. Limits apply per organization and project, not per user.

---

## 13. Chat Completions (brief; not recommended here) [S20, S7, S14]

* `POST /v1/chat/completions`, `messages[]` (`developer`/`user`/`assistant`/`tool`), `stream: true`, `stream_options: {"include_usage": true}`. The final usage chunk arrives before `data: [DONE]`.
* Chunk: `choices[].delta.content`, `choices[].delta.tool_calls[{index,id,function{name,arguments},type}]`, `finish_reason` ∈ `stop`, `length`, `tool_calls`, `content_filter`, `function_call`. Usage: `prompt_tokens`, `completion_tokens`, `prompt_tokens_details.cached_tokens|cache_write_tokens`, `completion_tokens_details.reasoning_tokens`.
* Web search only via model `gpt-5-search-api` + `web_search_options{search_context_size,user_location{type:"approximate",approximate{…}}}`. It always searches, with no domain filters or source list.
* **Tool calling is unavailable on Chat Completions for `gpt-6-astra` and `gpt-6.1-sol`.** `gpt-6-luna` and `gpt-6-sol` allow it only with `reasoning_effort: "none"`. → Use Responses.

---

## 14. Alternative auth options at OpenAI (instead of an API key)

### 14.1 Sign in with ChatGPT: "ChatGPT plan usage" [S23]

OpenAI's counterpart to "use your subscription instead of an API key":

* Users sign in with their ChatGPT account (OAuth 2 + OIDC + PKCE, **no client secret**). Eligible **ChatGPT Plus and Pro** users can let the app send **Responses API** requests that count against their **ChatGPT plan** instead of API billing. The app gets no access to their conversations or API keys.
* Availability: "ChatGPT plan usage is available to all open-source partners and selected private clients". The docs cover **open-source and locally hosted apps**. Paid or remotely hosted apps go through an interest form. Sign-in-only for commercial websites is a limited trial.
* Flow: open the system browser at `https://auth.openai.com/api/accounts/authorize`. On first registration use `client_id=dynamic_agent_client` with `agent_name_hint` and a stable `ext_agent_host_id` (`urn:uuid:…` or a JWK-thumbprint URI). Scopes: `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`. `resource=https://api.openai.com/v1`. PKCE S256. **`redirect_uri` must be an HTTP loopback on `127.0.0.1`** (e.g. `http://127.0.0.1:1455/auth/callback`; only the port may vary; `localhost` is not allowed). Exchange the code at `https://auth.openai.com/api/accounts/oauth/token`, then save the issued `client_id` (`oaiapp_…`).
* Tokens: the access token is valid for 1 h. The refresh token is valid for 30 days and rotates on every refresh. Use it as `Authorization: Bearer <access_token>` against `POST https://api.openai.com/v1/responses`.
* Restrictions: **`store: false` and `stream: true` required**. History goes in `input` (no `previous_response_id`). No `max_output_tokens`, `temperature`, `metadata`, `max_tool_calls`, `safety_identifier` and others. **Function tools must be grouped in a `namespace`** (or sent via `additional_tools`). Web search is "subject to model and account/workspace policy". File search, code interpreter and other built-ins are not supported. Models come from `GET /v1/models` with the access token (`models[].slug`, `display_name`, `visibility`).
* Plan errors: `subscription_sharing_usage_limit_exceeded` (429), `subscription_sharing_user_not_eligible` (403), `subscription_sharing_usage_unavailable` (503), `subscription_sharing_unsupported_capability` (400), etc. They can also arrive mid-stream as `response.failed`.
* Android fit: technically feasible (a loopback listener inside the app plus a Custom Tab), but the docs do not mention mobile apps. Whether a closed-source personal app qualifies is unclear (see *Open questions*).

### 14.2 Workload identity federation [S24, S18]

Exchanges a short-lived identity-provider token (OIDC JWT, SPIFFE, X.509; AWS/GCP/Azure/K8s/GitHub Actions) for a short-lived OpenAI access token mapped to a **Platform service account**. It is meant for **server workloads**, not for end users on a phone, so it is not applicable to this app.

---

## 15. Gotchas

1. `platform.openai.com/docs` moved to `developers.openai.com/api/docs` (301). Append `.md` for clean markdown.
2. `gpt-6-astra` and `gpt-6.1-sol` reject `reasoning.effort: "none"` (400). `gpt-6.1-sol` also rejects `minimal`. With effort ≠ `none`, `temperature`, `top_p` and `top_logprobs` must be absent [S13, S14].
3. Tool calling on GPT-6 needs **Responses** [S11, S14].
4. `store` defaults to `true` (30-day retention). Set `false` and replay history, including `reasoning` items with `encrypted_content` for tool loops [S10, S13].
5. `web_search.user_location` defaults to **US**. Always send DE/Berlin [S10].
6. No recency filter on web search. Put "now" and the time window in the prompt.
7. `function_call_arguments.done` has no `name`. Get it from `output_item.added`.
8. GPT-6 can emit a `message` with `phase: "commentary"` (a preamble) before tool calls. Do not show it as the answer, and do not count it as "no articles". Resend `phase` when replaying assistant messages [S10, S13].
9. Citation URLs may carry `utm_source=chatgpt.com` [S10 example]. Normalize before dedupe.
10. Annotation `start_index`/`end_index` are "character" indices. Whether they count Unicode code points or UTF-16 units is undocumented, so do not slice Kotlin strings with them blindly. Show sources as a list instead.
11. `usage` has no search count and no cost. Count `web_search_call` items yourself.
12. Implicit prompt caching writes cost 1.25× on GPT-5.6+ (§9).
13. Strict schemas: no `format: "uri"`, no bare `{"type":"null"}` (§7.4).
14. 429 is ambiguous: always read `error.code` (`credit_balance_exhausted` vs `slow_down` vs spend limits) [S16].
15. Long runs (reasoning + several searches) can take minutes. Set generous OkHttp read timeouts (call timeout 0, read timeout ≥ 120 s between events; this is our choice, not from the docs). `background: true` exists for very long jobs [S7].
16. Reasoning summaries need org verification. They are not needed here [S13].
17. GPT-6 Astra has async misalignment monitoring, which can stop a conversation (`misalignment_policy_violation`) [S9, S14].
18. Responses API `instructions` is not carried over when using `previous_response_id`, so it must be resent on each request [S10].

---

## 16. Open questions (not answerable from the docs on 2026-10-09)

1. Are search-content tokens included in `usage.input_tokens`? (Pricing says they are "billed at model rates"; the usage schema has no separate field.)
2. Are `open_page` / `find_in_page` actions billed as web-search calls? (Only "search actions incur a tool call cost" is stated.)
3. Does `parallel_tool_calls: false` also limit built-in web searches to one?
4. Are `format: "uri"` and `{"type":"null"}` rejected (400) or ignored in strict function schemas? Test once.
5. Unit of `url_citation.start_index`/`end_index` (code points vs UTF-16).
6. Minimum allowed `max_output_tokens` for the billing probe.
7. Official API-key prefix and length spec (only community sources are available).
8. Whether a personal, closed-source Android app qualifies for "ChatGPT plan usage" (Sign in with ChatGPT), and whether loopback redirects on Android are accepted.
9. Whether `web_search` quality is acceptable with `gpt-6-luna` at `reasoning.effort: "none"` (no statement for GPT-6; use `low`).
