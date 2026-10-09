# Provider spec: OpenAI-compatible providers (Mistral, xAI, DeepSeek, Perplexity, OpenRouter, self-hosted)

**AS_OF: 2026-10-09.** Every fact below comes from the providers' official documentation, OpenAPI files or
public model-list endpoints, fetched on 2026-10-09. Anything not found in those sources is marked
**UNVERIFIED** or listed under *Open questions* (§10). Nothing here comes from memory. Where a JSON example
is "illustrative", its shape is taken from the provider's schema but the values are made up.

Target: Morgenbriefing, Android (Kotlin, OkHttp 5, kotlinx-serialization, minSdk 24), plain HTTPS + SSE, no vendor SDK.
Companion specs: `openai.md` (Responses API, reused below for xAI and Perplexity), `gemini.md`.

---

## 0. Sources (all fetched 2026-10-09)

| # | Page | URL |
|---|---|---|
| M1 | Mistral API reference (Chat Completions) | https://docs.mistral.ai/api |
| M2 | Mistral OpenAPI file (schemas `ChatCompletionRequest`, `CompletionChunk`, `DeltaMessage`, `ToolCall`, `UsageInfo`, `ReasoningEffort`, `ThinkChunk`, `ConversationRequestBase`, `SSETypes`, `ConversationEvents`, `*Event`, `ToolReferenceChunk`, `ConversationUsageInfo`, `FunctionCallEntry`, `FunctionResultEntry`) | https://docs.mistral.ai/openapi.yaml |
| M3 | Mistral web search tool (old URL `/agents/connectors/websearch/` redirects here) | https://docs.mistral.ai/studio/agents/agent-tools/websearch |
| M4 | Mistral agent tools overview | https://docs.mistral.ai/studio/agents/agent-tools |
| M5 | Mistral Beta Conversations reference | https://docs.mistral.ai/api/endpoint/beta/conversations |
| M6 | Mistral function calling (Chat Completions) | https://docs.mistral.ai/studio/conversations/function-calling |
| M7 | Mistral function calling (Agents/Conversations) | https://docs.mistral.ai/studio/agents/agent-tools/function-calling |
| M8 | Mistral models overview (now `/models`) | https://docs.mistral.ai/getting-started/models/models_overview/ |
| M9 | Mistral model pages (API names in page data) | https://docs.mistral.ai/models/mistral-large-4-0 , …/mistral-medium-3-5-26-04 , …/mistral-small-4-0-26-03 , …/mistral-large-3-25-12 , …/ministral-3-8b-25-12 |
| M10 | Mistral pricing | https://docs.mistral.ai/inference/pricing , https://mistral.ai/pricing |
| M11 | Mistral changelog | https://docs.mistral.ai/getting-started/changelog/ |
| M12 | Mistral error glossary | https://docs.mistral.ai/resources/error-glossary |
| M13 | Mistral first API request (troubleshooting table) | https://docs.mistral.ai/getting-started/quickstarts/developer/first-api-request |
| M14 | Mistral usage and limits | https://docs.mistral.ai/admin/user-management-finops/tier |
| X1 | xAI models | https://docs.x.ai/docs/models |
| X2 | xAI pricing (incl. tools pricing) | https://docs.x.ai/developers/pricing |
| X3 | xAI web search tool | https://docs.x.ai/developers/tools/web-search |
| X4 | xAI citations | https://docs.x.ai/developers/tools/citations |
| X5 | xAI tools overview | https://docs.x.ai/developers/tools/overview |
| X6 | xAI Responses reference | https://docs.x.ai/developers/rest-api-reference/inference/responses |
| X7 | xAI Chat Completions reference | https://docs.x.ai/developers/rest-api-reference/inference/chat-completions |
| X8 | xAI "Comparison with Chat Completions" | https://docs.x.ai/developers/model-capabilities/text/comparison |
| X9 | xAI release notes | https://docs.x.ai/developers/release-notes |
| X10 | xAI advanced tool usage (mixing server + client tools) | https://docs.x.ai/docs/guides/tools/advanced-usage |
| X11 | xAI function calling | https://docs.x.ai/developers/tools/function-calling |
| X12 | xAI streaming & sync (tools) | https://docs.x.ai/developers/tools/streaming-and-sync |
| X13 | xAI cost tracking | https://docs.x.ai/developers/cost-tracking |
| X14 | xAI debugging errors | https://docs.x.ai/docs/key-information/debugging |
| X15 | xAI account endpoint (`GET /v1/api-key`) | https://docs.x.ai/developers/rest-api-reference/inference/other |
| X16 | xAI model pages | https://docs.x.ai/developers/models/grok-4.7 , https://docs.x.ai/developers/models/grok-4.3 |
| X17 | xAI rate limits (search-result snippets only) | https://docs.x.ai/docs/key-information/consumption-and-rate-limits |
| X18 | xAI old Live Search guide URL (now shows the Web Search tool page) | https://docs.x.ai/docs/guides/live-search |
| D1 | DeepSeek models & pricing | https://api-docs.deepseek.com/quick_start/pricing |
| D2 | DeepSeek error codes | https://api-docs.deepseek.com/quick_start/error_codes |
| D3 | DeepSeek home (base URLs, thinking toggle) | https://api-docs.deepseek.com/ |
| D4 | DeepSeek tool calls | https://api-docs.deepseek.com/guides/tool_calls |
| D5 | DeepSeek thinking mode | https://api-docs.deepseek.com/guides/thinking_mode |
| D6 | DeepSeek change log | https://api-docs.deepseek.com/updates |
| D7 | DeepSeek create chat completion | https://api-docs.deepseek.com/api/create-chat-completion |
| D8 | DeepSeek user balance | https://api-docs.deepseek.com/api/get-user-balance |
| D9 | DeepSeek rate limit | https://api-docs.deepseek.com/quick_start/rate_limit |
| P1 | Perplexity pricing | https://docs.perplexity.ai/docs/getting-started/pricing |
| P2 | Perplexity Sonar Chat Completions reference | https://docs.perplexity.ai/api-reference/chat-completions-post |
| P3 | Perplexity changelog | https://docs.perplexity.ai/changelog |
| P4 | Perplexity Agent API quickstart | https://docs.perplexity.ai/docs/agent-api/quickstart |
| P5 | Migrate from Sonar (overview) | https://docs.perplexity.ai/docs/agent-api/migrate-from-sonar/overview |
| P6 | Migrate from Sonar (how-to) | https://docs.perplexity.ai/docs/agent-api/migrate-from-sonar/how-to |
| P7 | Agent API custom functions | https://docs.perplexity.ai/docs/agent-api/tools/custom-functions |
| P8 | Agent API reference (create) | https://docs.perplexity.ai/api-reference/agent-post |
| P9 | Agent API models | https://docs.perplexity.ai/docs/agent-api/models |
| P10 | Agent API OpenAI compatibility | https://docs.perplexity.ai/docs/agent-api/openai-compatibility |
| P11 | Presets (search-result snippets) | https://docs.perplexity.ai/docs/agent-api/presets |
| P12 | FAQ and rate-limit tiers (search-result snippets) | https://docs.perplexity.ai/faq/faq , https://docs.perplexity.ai/docs/admin/rate-limits-usage-tiers |
| R1 | OpenRouter web search plugin (deprecated) | https://openrouter.ai/docs/guides/features/plugins/web-search |
| R2 | OpenRouter web search server tool | https://openrouter.ai/docs/guides/features/server-tools/web-search |
| R3 | OpenRouter server tools | https://openrouter.ai/docs/guides/features/server-tools |
| R4 | OpenRouter usage accounting | https://openrouter.ai/docs/guides/guides/usage-accounting |
| R5 | OpenRouter streaming | https://openrouter.ai/docs/api/reference/streaming |
| R6 | OpenRouter errors and debugging | https://openrouter.ai/docs/api/reference/errors-and-debugging |
| R7 | OpenRouter authentication | https://openrouter.ai/docs/api/reference/authentication |
| R8 | OpenRouter get current API key | https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key |
| R9 | OpenRouter limits | https://openrouter.ai/docs/api_reference/limits |
| R10 | OpenRouter tool calling | https://openrouter.ai/docs/guides/features/tool-calling |
| R11 | OpenRouter OpenAPI file (`ChatRequest`, `ChatStreamChunk`, `ChatStreamDelta`, `ChatStreamToolCall`, `ChatFinishReasonEnum`, `ChatUsage`, `ServerToolUseDetails`, `ProviderPreferences`, `ChatWebSearchShorthand`, `PublicPricing`) | https://openrouter.ai/openapi.yaml |
| R12 | OpenRouter public model list (live JSON, pulled 2026-10-09 ≈08:00 CEST) | https://openrouter.ai/api/v1/models |
| R13 | OpenRouter FAQ | https://openrouter.ai/docs/faq |
| L1 | Ollama OpenAI compatibility | https://docs.ollama.com/api/openai-compatibility |
| L2 | Ollama web search API | https://docs.ollama.com/capabilities/web-search |
| L3 | LM Studio OpenAI-compatible API | https://lmstudio.ai/docs/developer/openai-compat |
| L4 | LM Studio tool use | https://lmstudio.ai/docs/developer/openai-compat/tools |
| L5 | LM Studio authentication | https://lmstudio.ai/docs/developer/core/authentication |
| L6 | vLLM tool calling | https://docs.vllm.ai/en/latest/features/tool_calling.html |
| L7 | vLLM online serving / OpenAI-compatible server | https://docs.vllm.ai/en/latest/serving/online_serving/ , https://docs.vllm.ai/en/latest/serving/online_serving/openai_compatible_server/ |

---

## 1. TL;DR for the implementation

**The headline:** in October 2026, "OpenAI-compatible Chat Completions" fully covers both features
(chat + web search + function tools) only on **OpenRouter**. For the others:

| Provider | Chat ("Frag nach") | Web search | Function tools | Search + function in one request | Wire format to implement |
|---|---|---|---|---|---|
| **OpenRouter** | `POST https://openrouter.ai/api/v1/chat/completions` | Server tool `{"type":"openrouter:web_search"}` in `tools[]` (plugin `web` / `:online` are **deprecated**) [R1, R2] | Yes, OpenAI format [R10] | **Yes**, documented: server tools run at OpenRouter, user tools come back to you [R3] | **Chat Completions** (shared parser, §2) |
| **Mistral** | `POST https://api.mistral.ai/v1/chat/completions` | **Not on Chat Completions.** Only on the Conversations/Agents API (`POST /v1/conversations`), tool `{"type":"web_search"}` or `web_search_premium` [M3, M4] | Yes, on both APIs [M6, M7] | Yes on Conversations (one `tools[]` with `web_search` + `function`, per schema [M2]); mixing not explicitly confirmed in prose | Chat Completions for search-free chat, **plus a custom Conversations SSE parser** for search (§3.5) |
| **xAI (Grok)** | `/v1/chat/completions` is **deprecated** and "function calling only" [X7, X8] | Only on the **Responses API** `POST https://api.x.ai/v1/responses`, tool `{"type":"web_search"}` [X3, X8] | Yes [X11] | **Yes**, documented [X10] | **OpenAI Responses** (reuse the OpenAI adapter, §4) |
| **DeepSeek** | `POST https://api.deepseek.com/chat/completions` | **None** (no built-in search anywhere in the docs) [D1, D3, D4] | Yes [D4] | n/a. Live News needs a client-side search function (§2.6) | Chat Completions (shared parser) |
| **Perplexity** | Sonar Chat Completions **support ended 2026-09-27**; sync/stream calls "keep working" while being reformulated into Agent API calls [P2, P5] | Built into the **Agent API** `POST https://api.perplexity.ai/v1/agent` (alias `/v1/responses`), tool `{"type":"web_search"}` [P4, P8] | Yes, Agent API `{"type":"function",…}` [P7, P8] | Yes (server-side web_search + your function_call) [P7 via search snippet, P8] | **OpenAI Responses** with Perplexity extras (§6) |
| **Self-hosted** (Ollama, LM Studio, vLLM) | `{base}/v1/chat/completions` [L1, L3, L7] | **None** built in. Ollama offers a separate web-search REST API usable as a client tool [L2] | Depends on server and model [L1, L4, L6] | Only via client-side search function (§2.6) | Chat Completions (shared parser) |

**Architecture recommendation:** two generic adapters plus one special case.
1. `ChatCompletionsAdapter` (§2): OpenRouter, DeepSeek, Mistral chat without search, Ollama/LM Studio/vLLM. Per-provider quirks are a small config object (§2.5).
2. `ResponsesAdapter` (from `openai.md`): OpenAI, **xAI**, **Perplexity Agent API**. Per-provider differences in §4 and §6.
3. `MistralConversationsAdapter` (§3.5): only needed if Mistral web search is wanted. Otherwise offer Mistral "chat without web search" and no Live News.

**Recommended models (AS_OF 2026-10-09, USD per 1M tokens; details per provider below)**

| Provider | Top | Default | Cheap / fast | Web search surcharge |
|---|---|---|---|---|
| OpenRouter | `anthropic/claude-opus-5.5` (4 / 20) | `google/gemini-3.8-flash` (0.75 / 3.75) | `openai/gpt-6-luna` (0.10 / 0.50) | Native search: model's `pricing.web_search` (e.g. $0.014/search Gemini, $0.01 OpenAI/Anthropic). Exa: $0.007/request [R2, R12] |
| Mistral | `mistral-large-4` (preview; 1.36 / 4.18 list, 0.68 / 2.09 launch sale) | `mistral-medium-3-5` (1.5 / 7.5) or budget `mistral-large-2512` (0.5 / 1.5) | `mistral-small-2603` (0.15 / 0.6) | **Not published** in the fetched pricing pages ("tool APIs are priced per call") [M10] |
| xAI | `grok-4.7` (2 / 6) | `grok-4.3` (1.25 / 2.50) | `grok-4.3` with `reasoning.effort: "none"`, or `grok-4.20-0309-non-reasoning` (same price) | `web_search` **$5 / 1k calls** [X2] |
| DeepSeek | `deepseek-v4-pro` (peak 1.32 / 3.96) | `deepseek-flash` (peak 0.30 / 1.20) | `deepseek-flash` with thinking disabled | n/a (no search) |
| Perplexity (Agent API) | `anthropic/claude-opus-5-5` (4 / 20) or `preset: "high"` | `google/gemini-3.8-flash` (0.75 / 3.75) or `preset: "low"` | `openai/gpt-6-luna` (0.10 / 0.50) or `perplexity/sonar` (0.25 / 2.50) | `web_search` $0.0025/invocation; `search_type:"fast"` $0.001 [P1] |
| Self-hosted | – | – | – | free (no search) |

---

## 2. Shared Chat Completions wire format (OpenRouter, DeepSeek, Mistral chat, self-hosted)

### 2.1 Request skeleton

```http
POST {base}/chat/completions
Authorization: Bearer <key>
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "model": "<id>",
  "stream": true,
  "messages": [
    { "role": "system", "content": "Du bist der Nachrichten-Assistent von Morgenbriefing. Antworte immer auf Deutsch …\n\nKONTEXT (Artikel):\nTitel: …\nQuelle: …\n\n<Artikeltext / Briefing>" },
    { "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" },
    { "role": "assistant", "content": "…frühere Antwort…" },
    { "role": "user", "content": "Und seit wann gilt das?" }
  ],
  "max_tokens": 2000,
  "temperature": 0.3
}
```

Per-provider additions and **forbidden fields** are in §2.5. Do **not** send one superset body to every provider:
Mistral's `ChatCompletionRequest` has `additionalProperties: false` and no `stream_options` field [M2], so unknown
fields are expected to fail validation (422, **UNVERIFIED** in a live call).

### 2.2 SSE framing

* Lines `data: {json}` separated by a blank line. The stream ends with `data: [DONE]` (Mistral [M2], DeepSeek [D7], OpenRouter [R5]).
* **Comment lines start with `:`. Skip them before JSON parsing.** OpenRouter sends `: OPENROUTER PROCESSING` [R5]. DeepSeek sends `: keep-alive` while waiting (non-streaming requests get empty lines instead) [D9].
* No `event:` lines are used on Chat Completions. (Mistral *Conversations* uses `event:` lines, §3.5.)
* Errors **before** the stream starts come back as a normal HTTP error with a JSON body (§9). Errors **after** the stream has started arrive as a chunk with HTTP 200 (OpenRouter: §7.6; Mistral: `finish_reason: "error"`; DeepSeek: `insufficient_system_resource` / `aborted`).

### 2.3 Chunk fields to parse

```json
{"id":"…","object":"chat.completion.chunk","created":1760000000,"model":"…",
 "choices":[{"index":0,
   "delta":{"role":"assistant","content":"Text…","reasoning_content":null,
            "tool_calls":[{"index":0,"id":"call_…","type":"function","function":{"name":"publish_articles","arguments":"{\"art"}}]},
   "finish_reason":null}],
 "usage":null}
```

| Field | Meaning | Provider notes |
|---|---|---|
| `choices[0].delta.content` | Answer text delta | Mistral: `string \| null \| array of ContentChunk`. Reasoning models can send `[{"type":"thinking","thinking":[{"type":"text","text":"…"}]}]` (`ThinkChunk`) [M2]. **Handle both string and array.** |
| `delta.reasoning_content` | Reasoning text | DeepSeek, thinking mode only [D5, D7] |
| `delta.reasoning` / `delta.reasoning_details` | Reasoning text / structured reasoning | OpenRouter [R11] |
| `delta.refusal` | Refusal text | OpenRouter [R11] |
| `delta.tool_calls[]` | `index`, `id`, `type:"function"`, `function.name`, `function.arguments` (string fragment) | See §2.4 |
| `choices[0].finish_reason` | End of the choice | Values per provider in §2.5 |
| `usage` | Token usage | Where it appears differs (§2.5) |
| top-level `error` | Mid-stream error | OpenRouter only [R5, R6, R11] |

### 2.4 Tool-call accumulation (one algorithm for all)

```
calls: Map<index, Acc{id, name, args: StringBuilder}>
for each tc in delta.tool_calls:
    acc = calls.getOrPut(tc.index ?: 0)
    tc.id?.let { if (it.isNotEmpty() && it != "null") acc.id = it }      // Mistral ToolCall.id default is the string 'null' [M2]
    tc.function?.name?.let { acc.name = it }
    tc.function?.arguments?.let { acc.args.append(it) }
on finish_reason == "tool_calls" (or stream end with calls.isNotEmpty()): parse each acc.args as JSON
```

* DeepSeek: "The first chunk for a tool call carries `id`, `type`, and `function`. Later chunks carry only the arguments" [D7].
* LM Studio: first chunk carries `id` and `name`, later chunks `arguments` fragments, keyed by `index` [L4].
* xAI Chat Completions: "the function call is returned in whole in a single chunk" [X11]. vLLM: "each tool call is emitted whole once its region parses" [L6]. The algorithm above handles both.
* Mistral: `ToolCall` has `index` (default 0) [M2]. Whether arguments are streamed in fragments is not documented (**UNVERIFIED**). The algorithm handles both cases.

Tool result message (all providers):

```json
{ "role": "assistant", "content": null, "tool_calls": [ { "id": "call_…", "type": "function", "function": { "name": "publish_articles", "arguments": "{…}" } } ] },
{ "role": "tool", "tool_call_id": "call_…", "content": "{\"ok\":true}" }
```

Mistral additionally shows `"name": "<function_name>"` on the tool message [M6]. DeepSeek thinking mode requires the assistant message to carry its `reasoning_content` too (§5.4).

**OpenRouter with reasoning upstreams (app note, 2026-10-09, UNVERIFIED against a live key):** Gemini 3 rejects a function call in the current turn without its `thought_signature` (HTTP 400, GE §7.4), and Claude with thinking needs its thinking block before the `tool_use`. OpenRouter streams both as `delta.reasoning_details` (§2.3). The app keeps them per response — items merged by `index` (else `id`), `text`/`summary`/`data`/`signature` pieces appended, everything else as received — and puts them as `"reasoning_details": […]` on the replayed `{"role":"assistant","content":null,"tool_calls":[…]}` of a Live correction. Only for OpenRouter: Mistral rejects unknown fields. Check once with a real key on `google/gemini-3.8-flash` and `anthropic/claude-sonnet-5.5` (a `live-reject`-style correction) whether OpenRouter accepts the echo and the upstream no longer answers 400.

Tool definition (all Chat Completions providers): `{"type":"function","function":{"name","description","parameters"}}`. Mistral, DeepSeek and OpenRouter document an optional `function.strict` [M2, D7, R11].

### 2.5 Provider matrix for the shared parser

| | Mistral | DeepSeek | OpenRouter | Ollama | LM Studio | vLLM |
|---|---|---|---|---|---|---|
| Base URL | `https://api.mistral.ai/v1` [M1] | `https://api.deepseek.com` (strict tools: `https://api.deepseek.com/beta`) [D3, D4] | `https://openrouter.ai/api/v1` [R7, R8] | `http://<host>:11434/v1/` (cloud: `https://ollama.com/v1`) [L1] | `http://<host>:1234/v1` [L3] | `http://<host>:8000/v1` (from the docs' client example) [L6, L7] |
| `stream_options` | **Not in schema; do not send** [M2] | `{"include_usage":true}` allowed; must be used with `stream:true` or 400 [D7] | Deprecated, "has no effect"; usage always included [R4, R11] | `include_usage` supported [L1] | not documented | not documented |
| Usage location | `usage` field on `CompletionChunk` [M2]; on which chunk is **UNVERIFIED** (assume last) | `usage` on the **last content chunk** (the one with non-null `finish_reason`); "no separate usage-only chunk" [D7] | Extra final chunk before `[DONE]` with **one choice, empty delta, repeating `finish_reason`**. Treat it as accounting, not a second end [R5] | with `include_usage` | ? | ? |
| `finish_reason` values | `stop`, `length`, `error`, `tool_calls` [M2] | `stop`, `length`, `content_filter`, `tool_calls`, `insufficient_system_resource`, `aborted` [D7] | `tool_calls`, `stop`, `length`, `content_filter`, `error` [R11] | ? | `tool_calls` (non-streaming example) [L4] | ? |
| `tool_choice` | `auto` (default), `none`, `any`, `required`, or `{"type":"function","function":{"name":…}}` [M2] | `none`, `auto`, `required`, named. **`required` and named return 400 in thinking mode** [D7] | `auto` (default), `none`, named [R10] | **not supported** [L1] | not documented [L4] | `auto`, `required` (vLLM ≥ 0.8.3), `none`, named [L6] |
| `parallel_tool_calls` | yes, default `true` [M2] | not documented | yes, default true for most models [R10] | ? | ? | yes, default `true` [L7] |
| Reasoning control | `reasoning_effort`: `none` `minimal` `low` `medium` `high` `xhigh` (schema) [M2]; `prompt_mode: "reasoning"` [M1] | `thinking: {"type":"enabled"\|"disabled"}` (default **enabled**), `reasoning_effort`: `none` `low` `high` `max` (default `high`) [D5, D7] | `reasoning: {effort, summary}`, also `reasoning_effort` [R11] | `reasoning_effort` / `reasoning.effort`, model-defined names [L1] | ? | ? |
| Max tokens field | `max_tokens` [M2] | `max_tokens` 1–393216; default 8K (non-thinking), 64K (thinking), 128K (`max` effort) [D7] | `max_tokens` or `max_completion_tokens` [R11] | `max_tokens` [L1] | ? | ? |

`?` = not documented in the fetched pages.

### 2.6 Live News on providers without built-in search (DeepSeek, self-hosted)

There is no documented one-request pattern. The only option is a **client-side search function**, which is a multi-round loop:
1. Tools: `web_search(query: string)` (our function) + `publish_articles` (§2.4 format).
2. The model calls `web_search` → the app runs a real search → returns results as a `role:"tool"` message → repeat until the model calls `publish_articles`.
3. Search backends found in this research: Ollama web search API (`POST https://ollama.com/api/web_search`, Bearer `OLLAMA_API_KEY`, `{"query", "max_results" (default 5, max 10)}` → `results[{title,url,content}]`; pricing/quotas not stated, "A free Ollama account is required") [L2]; Perplexity Search API ($5.00 / 1k requests, fast mode $1.00 / 1k) [P1].

This needs a second key and more round trips. **Recommendation:** hide Live News for DeepSeek and self-hosted unless such a search key is configured.

### 2.7 Kotlin parser sketch (OkHttp 5, no okhttp-sse needed)

```
response.body.source().use { src ->
  while (!src.exhausted()) {
    val line = src.readUtf8Line() ?: break
    if (line.isEmpty() || line.startsWith(":")) continue          // keep-alive / OPENROUTER PROCESSING
    if (!line.startsWith("data:")) continue                        // no event: lines on Chat Completions
    val payload = line.removePrefix("data:").trim()
    if (payload == "[DONE]") break
    val chunk = json.parseToJsonElement(payload).jsonObject       // Json { ignoreUnknownKeys = true }
    chunk["error"]?.let { emitError(mapMidStreamError(it)); return }   // OpenRouter
    chunk["usage"]?.takeIf { it !is JsonNull }?.let { usage = it }
    chunk["annotations"] / choices[0].delta["annotations"] -> citations (OpenRouter, defensive, §7.5)
    val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
    val delta = choice["delta"]?.jsonObject
    delta?.get("content") -> string: append; array: text chunks append, "thinking" chunks ignore (Mistral)
    delta?.get("tool_calls") -> accumulate (§2.4)
    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finish = it }   // keep reading: usage may follow (OpenRouter)
  }
}
```

---

## 3. Mistral

### 3.1 Endpoint and auth

| Item | Value | Source |
|---|---|---|
| Base URL | `https://api.mistral.ai/v1` | M1 |
| Chat | `POST /v1/chat/completions` | M1 |
| Conversations (web search) | `POST /v1/conversations` (start), `POST /v1/conversations/{conversation_id}` (append), `POST /v1/conversations/{conversation_id}/restart`; streaming via `stream: true` | M5 |
| Models | `GET /v1/models` ("List all models available to the user") | M2 |
| Auth | `Authorization: Bearer <MISTRAL_API_KEY>` | M1 |
| Key format | Not documented (no prefix). The key is shown only once [M13] | M13 |

### 3.2 Models and prices (AS_OF 2026-10-09; USD per 1M tokens; API names from the model pages' data) [M9, M10, M11]

| Role | API name(s) | Context | Input | Cached input | Output | Notes |
|---|---|---|---|---|---|---|
| **Top** | `mistral-large-4` (also `mistral-large-4-0`) | 1M | **0.68** sale (list 1.36) | 0.07 (list 0.14) | **2.09** sale (list 4.18) | Public Preview since 2026-10-06, MoE 52B active / 1.05T. "Launch pricing is 50% off for two weeks" [M11] → list price from about 2026-10-20 |
| **Default** | `mistral-medium-3-5` (aliases `mistral-medium-3`, `mistral-medium-latest`) | 256k | 1.5 | 0.15 | 7.5 | GA 2026-04-28, has `reasoning_effort` [M11]. Mistral's own web-search example uses `mistral-medium-latest` [M3] |
| Budget default | `mistral-large-2512` (alias `mistral-large-latest`) | 256k | 0.5 | 0.05 | 1.5 | Mistral Large 3, GA 2025-12-02. Lists Agents & Conversations and built-in tools |
| **Cheap / fast** | `mistral-small-2603` (alias `mistral-small-latest`) | 256k | 0.15 | 0.015 | 0.6 | Mistral Small 4, hybrid instruct/reasoning, built-in tools listed |
| Tiny | `ministral-8b-2512` (alias `ministral-8b-latest`) | 256k | 0.15 | 0.015 | 0.15 | Model page lists no built-in tools |

* Batch costs 50% less [M10]. Pricing pages also have Priority/regional tabs (not needed).
* Deprecated: `mistral-medium-2508` and `mistral-medium-2505` (retired 2026-08-31), `mistral-small-2506` and `magistral-medium-2509` (retired 2026-07-31), `mistral-large-2411` (retired 2026-05-31) [M8].
* **Pin dated names.** `mistral-large-latest` still points to Large 3 (2512) while Large 4 is in preview [M9].
* **Web search price:** the public pricing pages say only "tool APIs are priced per call", with no rate for `web_search` or `web_search_premium` [M10]. → *Open questions*.

### 3.3 Chat Completions request ("Frag nach" without web search)

```json
{
  "model": "mistral-medium-latest",
  "stream": true,
  "temperature": 0.3,
  "max_tokens": 2000,
  "messages": [
    { "role": "system", "content": "Du bist … Antworte auf Deutsch …\n\nKONTEXT:\n…" },
    { "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" }
  ]
}
```

* Allowed extra fields: `reasoning_effort`, `prompt_mode`, `parallel_tool_calls`, `response_format` (`text` / `json_object` / `json_schema`), `prompt_cache_key`, `service_tier` (`auto` / `standard_only`), `safe_prompt`, `random_seed`, `guardrails`, `metadata` [M1, M2]. **Nothing else**: `additionalProperties: false` [M2].
* `temperature` 0–1.5. Mistral recommends 0.0–0.7 [M2].
* `tools` in the schema also accepts `web_search` types, **but the docs say they "aren't supported in the Chat Completions API"** because Chat Completions can't return the search references [M3, M4]. Don't send them.

### 3.4 Chat Completions streaming

Shared parser (§2). Mistral specifics [M2]: `finish_reason` ∈ `stop`, `length`, `error`, `tool_calls`. `delta.content` may be an array (`ThinkChunk` with `type:"thinking"`, `thinking[]`, `signature`). `usage` = `{prompt_tokens, completion_tokens, total_tokens, prompt_audio_seconds?, service_tier?}`. The chat `UsageInfo` schema has **no cached-token field**. Another `UsageInfo` variant in the same OpenAPI file has `prompt_tokens_details.cached_tokens` and `num_cached_tokens`, so parse both optionally (**UNVERIFIED** for chat).

### 3.5 Web search: Conversations API (custom SSE)

**Request** (stateless, no agent needed; `model` + `tools` directly on the conversation per `ConversationRequestBase` [M2, M7]):

```http
POST https://api.mistral.ai/v1/conversations
Authorization: Bearer …
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "model": "mistral-medium-latest",
  "stream": true,
  "store": false,
  "instructions": "Du bist der Nachrichten-Assistent von Morgenbriefing. Antworte immer auf Deutsch … Nutze web_search nur für Hintergrund …\n\nKONTEXT (Artikel):\n…",
  "inputs": [
    { "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" }
  ],
  "tools": [ { "type": "web_search" } ],
  "completion_args": { "temperature": 0.3, "max_tokens": 2000, "tool_choice": "auto" }
}
```

* `inputs`: a string, or an array of entries. Message entries are `{"role":"user"|"assistant","content":…}` with optional `"type":"message.input"`. **There is no `system` role in input entries**, so the system prompt goes into `instructions` [M2].
* History entries may also be `message.output`, `function.call`, `function.result` and `tool.execution` entries [M2, `InputEntries`]. This allows stateless multi-turn replay with `store: false` (per schema, **UNVERIFIED** live).
* `completion_args` accepts `stop`, `presence_penalty`, `frequency_penalty`, `temperature`, `top_p`, `max_tokens`, `random_seed`, `prediction`, `response_format`, `tool_choice`, `reasoning_effort` [M2].
* Tool types: `function`, `web_search`, `web_search_premium` (adds "news articles via integrated news provider verification" [M3]), `code_interpreter`, `image_generation`, `document_library`, `connector`. Built-in tools accept an optional `tool_configuration {include, exclude, requires_confirmation}` [M2].

**SSE events** (`SSETypes`, each sent as `event: <type>` + `data: {…, "type": "<type>"}`) [M2]:

| Event | Fields | Use |
|---|---|---|
| `conversation.response.started` | `conversation_id`, `created_at` | Keep `conversation_id` (needed to append a function result) |
| `tool.execution.started` | `id`, `name` (e.g. `web_search`), `arguments` (string), `output_index` | UI status "Suche läuft…" |
| `tool.execution.delta` | `id`, `name`, `arguments` | ignore |
| `tool.execution.done` | `id`, `name`, `info` (object) | count searches |
| **`message.output.delta`** | `id`, `output_index`, `content_index`, `role:"assistant"`, **`content`**: string **or** one chunk object (`text`, `thinking`, **`tool_reference`**, …) | string → append text. `{"type":"tool_reference",…}` → citation |
| **`function.call.delta`** | `id`, `tool_call_id`, `name`, **`arguments`** (string), `output_index`, `confirmation_status?` | Accumulate per `tool_call_id` (Live News) |
| `agent.handoff.started` / `.done` | – | ignore |
| **`conversation.response.done`** | **`usage`** | Terminal success |
| **`conversation.response.error`** | `message`, `code` (integer) | Terminal error |

`ToolReferenceChunk` = `{"type":"tool_reference","tool":"web_search","title":"…","url":"…"|null,"favicon"?,"description"?}`. `tool` and `title` are required [M2]. The prose docs also mention a `source` field [M3]. Parse it optionally.

Conversation `usage` (`ConversationUsageInfo`) = `{prompt_tokens, completion_tokens, total_tokens, connector_tokens?, connectors?: {<name>: int}}` [M2]. The meaning of the `connectors` map (calls per connector?) is not described → **UNVERIFIED**. Use it as the search count if it holds `web_search`.

Illustrative stream (shape per schema; **not a captured trace**):

```
event: conversation.response.started
data: {"type":"conversation.response.started","conversation_id":"conv_…","created_at":"…"}

event: tool.execution.started
data: {"type":"tool.execution.started","id":"…","name":"web_search","arguments":"{\"query\":\"…\"}","output_index":0}

event: message.output.delta
data: {"type":"message.output.delta","id":"…","output_index":1,"content_index":0,"role":"assistant","content":"Laut …"}

event: message.output.delta
data: {"type":"message.output.delta","id":"…","output_index":1,"content_index":1,"role":"assistant","content":{"type":"tool_reference","tool":"web_search","title":"…","url":"https://…"}}

event: conversation.response.done
data: {"type":"conversation.response.done","usage":{"prompt_tokens":…,"completion_tokens":…,"total_tokens":…,"connector_tokens":…,"connectors":{…}}}
```

A `data: [DONE]` terminator is **not documented** for Conversations. End on `conversation.response.done` / `.error` (and tolerate `[DONE]`).

### 3.6 Function calling

* Chat Completions: §2.4. `function.arguments` is a JSON string [M6].
* Conversations: the model emits a `function.call` output entry (`tool_call_id`, `name`, `arguments` as JSON string or object; streamed as `function.call.delta`) [M2, M7]. Return the result with a `function.result` entry:

```json
POST /v1/conversations/{conversation_id}
{ "inputs": [ { "type": "function.result", "tool_call_id": "…", "result": "{\"ok\":true}" } ], "stream": true }
```

Append needs a stored conversation, so append's `store` defaults to `true` [M5]. With `store: false` on start, re-send the full entry list in a new `POST /v1/conversations` instead (per schema; **UNVERIFIED**).

### 3.7 Live News (Conversations, one request)

`tools: [{"type":"web_search_premium"}, {"type":"function","function":{"name":"publish_articles","description":"…","parameters":{…}}}]`.
The schema allows both in one array [M2]. Prose docs say only "You can use multiple tools at the same time!" [M4]. Mixing built-in and function tools is **not explicitly confirmed** → test once. Read `publish_articles` from `function.call.delta` (accumulate `arguments` by `tool_call_id`). Send a `function.result` only for the correction loop.

### 3.8 Key test, errors, rate limits

* **Key test:** `GET https://api.mistral.ai/v1/models` (lists models; no token cost) [M2].
* **Errors:** 400, 401 ("API key is incorrect or not set"), **402 "No payment method on account"** [M13], 403, 404, 422 (validation), 429, 500, 502, 503, 504 [M12]. The error body is `{"object":"error","message","type","param","code"}`, with `type` ∈ `invalid_request_error`, `authentication_error`, `rate_limit_error`, `server_error` [M12]. Retry 429/500/502/503/504 with exponential backoff [M12].
* **Rate limits:** per model, as requests per second and tokens per minute, shown in Admin Panel › API › Limits. Free mode has included monthly usage [M14]. Help-center snippets also mention a tokens-per-month cap, and that a workspace spending limit can return 429 until the next billing cycle (search snippets only, **UNVERIFIED**).

### 3.9 Gotchas

* Web search exists **only** on Conversations/Agents, which means a different SSE grammar.
* Mistral's strict request schema rejects foreign fields (no `stream_options`, no `max_completion_tokens`).
* `delta.content` / `message.output.delta.content` can be an object or array, not just a string.
* `mistral-large-latest` and `mistral-medium-latest` move silently. Pin `mistral-large-2512`, `mistral-medium-3-5`, `mistral-small-2603`.
* Mistral Large 4's sale price ends about 2026-10-20 [M11].

---

## 4. xAI (Grok)

### 4.1 Status and endpoint

| Item | Value | Source |
|---|---|---|
| Base URL | `https://api.x.ai/v1` (US regional: `https://us.api.x.ai/v1`, billed 1.1×, only `grok-4.7` and `grok-4.6`) | X2, X6 |
| **Use** | **`POST /v1/responses`** (OpenAI Responses-compatible; `messages` → `input`, `max_tokens` → `max_output_tokens`) | X6, X8 |
| Legacy | `POST /v1/chat/completions`: "the stateless, OpenAI-compatible predecessor of the Responses API"; marked "Deprecated"; "Currently, only functions are supported as a tool"; no native search | X7, X8 |
| Live Search | `search_parameters` (`mode` off/on/auto, `sources`, `from_date`, `to_date`, `max_search_results`, `return_citations`) **still appears in both schemas** [X6, X7], but the old Live Search guide URL now shows the Web Search *tool* page [X18], and the release notes from Oct 2025 on only promote `web_search` / `x_search` tools [X9]. **Do not use `search_parameters`.** | X6, X7, X9, X18 |
| Auth | `Authorization: Bearer <XAI_API_KEY>` | X6 |
| Branding | The docs now say "SpaceXAI" in places (e.g. "Built-in tools run on SpaceXAI's servers") | X5 |

### 4.2 Models and prices (AS_OF 2026-10-09; USD per 1M tokens; prompts ≥ 200k tokens are billed at the higher rate **for all tokens**) [X1, X2, X16]

| Role | Model ID | Context | Input | Cached | Output | Reasoning | Notes |
|---|---|---|---|---|---|---|---|
| **Top** | `grok-4.7` | 500k | 2.00 (≥200k: 4.00) | 0.50 (1.00) | 6.00 (12.00) | `low` `medium` `high` (default) `xhigh` | Released Sept 2026. On Responses it "always returns `reasoning.encrypted_content`" [X9]. 150 RPS / 50M TPM on the model page [X16] |
| **Default** | `grok-4.3` (alias `grok-4.3-latest`) | 1M | 1.25 (2.50) | 0.20 (0.40) | 2.50 (5.00) | `none` `low` (default) `medium` `high` `xhigh` | 37 RPS / 10M TPM on the model page [X16]. Batch −20 % |
| **Cheap / fast** | `grok-4.3` with `reasoning.effort:"none"`, or `grok-4.20-0309-non-reasoning` | 1M | 1.25 | 0.20 | 2.50 | – | There is **no cheaper public text model**. "Grok 4.7 Fast … is not available on the public xAI API" [X2] |
| Others | `grok-4.6`, `grok-4.5` (500k, 2/6), `grok-4.20-0309-reasoning`, `grok-4.20-multi-agent-0309`, `grok-build-0.1` (coding, 256k, 1/2) | | | | | | |

**Tool prices** [X2]: `web_search` **$5 / 1k calls**; `x_search` $5 / 1k posts, $10 / 1k profiles; `code_execution` $5 / 1k calls. Image search is billed at the web search rate. Priority processing costs 2×. In the Responses API, a "$0.05 usage guideline violation fee per request" applies.

### 4.3 Request ("Frag nach" with web search)

```json
{
  "model": "grok-4.3",
  "stream": true,
  "store": false,
  "instructions": "Du bist der Nachrichten-Assistent von Morgenbriefing. Antworte immer auf Deutsch …\n\nKONTEXT (Artikel):\n…",
  "input": [
    { "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" }
  ],
  "tools": [ { "type": "web_search" } ],
  "reasoning": { "effort": "low" },
  "max_output_tokens": 4000,
  "include": ["web_search_call.action.sources"]
}
```

* `instructions` "cannot be combined with `previous_response_id`" [X6]. With `store: false`, replay history in `input`.
* `store` defaults to on (kept 30 days) [X6, X8].
* `max_output_tokens` defaults to 128,000 when unset and counts only visible output [X6].
* `include` values for Responses: `"web_search_call.action.sources"` (all URLs consulted), `"reasoning.encrypted_content"`, and `"no_inline_citations"` (see §4.5) [X4, X6, X12].
* Web search parameters [X3]: `allowed_domains` **or** `excluded_domains` (max 5 each, not both), `enable_image_understanding`, `enable_image_search`. The page says these go under `"filters"` "in the OpenAI SDK form". Raw REST placement (top-level vs `filters`) is **UNVERIFIED**: use `filters` as with OpenAI.
* `safety_identifier` (opaque end-user id) is accepted since Sept 2026 [X9].

### 4.4 SSE format

Same grammar as OpenAI Responses (reuse `openai.md` §5). The xAI docs themselves name `response.output_text.delta`, `response.reasoning_text.delta`, `response.reasoning_summary_text.delta`, `response.output_item.done` and the terminal `response.completed` / `response.incomplete` (usage is on the terminal event) [X5, X12 and the search-indexed reasoning, X-Search and image-generation pages]. xAI does **not publish a full event list** [X6, X12] → parse by `type`, ignore unknown types, and tolerate `data: [DONE]` (documented for Responses streaming [X6]).
Function calls: "the function call is returned in whole in a single chunk" [X11] → take `name`, `call_id` and `arguments` from `response.output_item.done` (item `type:"function_call"`). Don't rely on `function_call_arguments.delta`.

### 4.5 Citations [X4]

* `output_text.annotations[]` = `{"type":"url_citation","url","start_index","end_index","title"}`. **`title` is the citation number** (e.g. `"1"`), **not the page title** → fetch titles elsewhere or display the domain.
* Inline citations are **on by default**: the text contains `[[1]](https://…)`. Send `"include": ["no_inline_citations"]` to turn them off (annotations may then have no positions).
* The response also has a `citations` list of every URL encountered ("always returned by default"). Its exact location in the Responses JSON is not shown [X4].

### 4.6 Usage and cost [X6, X13]

`usage` = `input_tokens`, `output_tokens`, `total_tokens`, `input_tokens_details.cached_tokens`, `output_tokens_details.reasoning_tokens`, **`num_sources_used`**, **`num_server_side_tools_used`**, **`server_side_tool_usage_details`** (e.g. `web_search_calls`, `x_search_calls`), **`cost_in_usd_ticks`** (**10,000,000,000 ticks = $1**), `cost_in_nano_usd`.
→ xAI reports the **exact charged cost** per request. On Chat Completions streaming, cost arrives only in the final chunk and only with `stream_options.include_usage: true` [X13].

### 4.7 Live News (one request, documented) [X10, X11]

`tools: [ {"type":"web_search"}, {"type":"function","name":"publish_articles","description":"…","parameters":{…}} ]`. The function definition is **flat** (no `function` wrapper) on Responses [X11]. Up to 350 tools [X6].
"When the model calls a client-side tool, execution pauses and the calls are returned to you" [X10]. Return results as `{"type":"function_call_output","call_id","output"}`, either with `previous_response_id` or by replaying `response.output` + outputs with `include:["reasoning.encrypted_content"]` [X10].
`tool_choice`: `auto` (default), `required`, `none`, or `{"type":"function",…}`. `parallel_tool_calls` defaults to on [X11]. `max_turns` limits only server-side tool turns within one request [X10].

### 4.8 Key test, errors, rate limits

* **Key test (free):** `GET https://api.x.ai/v1/api-key` → `redacted_api_key`, `api_key_id`, `user_id`, `team_id`, `name`, `acls[]`, **`api_key_blocked`**, **`api_key_disabled`**, **`team_blocked`**, `create_time`, `modify_time`, `modified_by` [X15].
* **Errors** [X14]: 400 (bad body/URL), **401** ("No authorization header or an invalid authorization token was provided"), **403** (no cause listed; "Ask your team admin for permission"), 404, 405, 415, 422 (invalid field format), **429** ("reached the rate limit"). 5xx and out-of-credit behaviour are **not documented** → on 403, call `GET /v1/api-key` and check `team_blocked` / `api_key_blocked` to label it billing vs permission.
* **Correction (OBSERVED 2026-10-09, curl with a fake key during the release QA):** a **wrong** key is **not** a 401. `GET /v1/api-key` and `POST /v1/responses` both answer **HTTP 400** with a flat body `{"code":"invalid-argument","error":"Incorrect API key provided. You can obtain an API key from https://console.x.ai."}`; only a **missing** key gets 401. → The key check treats a 400 that names the API key (and any other 4xx of `/api-key`, which judges only the key) as INVALID, and the chat maps a 400 naming the API key to `AiError.InvalidKey` ("Key ändern"), not to a generic "HTTP 400".
* **Rate limits** [X17, snippets]: per model, RPS and TPM. RPS = RPM / 48 (minimum 2). Cached tokens count toward TPM. Tiers depend on cumulative spend since 2026-01-01 ($0, $50, $250, $1,000, $5,000), and tiers never downgrade. Over the limit → 429.

---

## 5. DeepSeek

### 5.1 Endpoint and models

| Item | Value | Source |
|---|---|---|
| Base URL | `https://api.deepseek.com` (OpenAI format; `POST /chat/completions`). Anthropic format: `https://api.deepseek.com/anthropic`. Strict tools (beta): `https://api.deepseek.com/beta` | D3, D4, D7 |
| Responses API | "Native Responses API support" since 2026-08-13 [D6]; listed for both models [D1]. Not needed: no search | D1, D6 |
| Auth | `Authorization: Bearer ${DEEPSEEK_API_KEY}` | D3 |
| Web search | **None documented** | D1, D3, D4 |

**Models and prices (AS_OF 2026-10-09, USD per 1M)** [D1, D6]. Off-peak is half the peak price. Peak = **01:00–04:00 and 06:00–10:00 UTC, Mon–Fri** (excluding Chinese public holidays); everything else is off-peak.

| Role | Model | Version | Context / max out | Cache hit (off/peak) | Cache miss (off/peak) | Output (off/peak) | Concurrency |
|---|---|---|---|---|---|---|---|
| **Default / cheap** | `deepseek-flash` | DeepSeek-V4.1-Flash (released 2026-09-10, vision) | 1M / 384K | 0.003 / 0.006 | 0.15 / 0.30 | 0.60 / 1.20 | 2,500 |
| **Top** | `deepseek-v4-pro` | DeepSeek-V4-Pro-0813 | 1M / 384K | 0.022 / 0.044 | 0.66 / 1.32 | 1.98 / 3.96 | 500 |

* Legacy names `deepseek-v4-flash` and `deepseek-v4-flash-vision-exp` are routed to V4.1 Flash and billed at the Flash price [D1].
* `deepseek-chat` / `deepseek-reasoner` were announced to be "discontinued in three months (2026-07-24)" [D6] and no longer appear on the pricing page. Don't use them.

### 5.2 Request ("Frag nach")

```json
{
  "model": "deepseek-flash",
  "stream": true,
  "stream_options": { "include_usage": true },
  "thinking": { "type": "disabled" },
  "max_tokens": 2000,
  "messages": [
    { "role": "system", "content": "Du bist … Antworte auf Deutsch …\n\nKONTEXT:\n…" },
    { "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" }
  ]
}
```

* **Thinking is on by default with effort `high`** [D5, D7]. For chat, disable it (`thinking.type:"disabled"` or `reasoning_effort:"none"`) or set `reasoning_effort:"low"`. Effort values: `none`, `low`, `high`, `max`. `minimal` maps to `low`; `medium` and `xhigh` map to `high` [D7].
* In thinking mode, `temperature`, `presence_penalty` and `frequency_penalty` have no effect, and `top_p` < 0.95 is treated as 0.95 [D5]. `frequency_penalty` and `presence_penalty` are deprecated [D7].
* `response_format`: `text` | `json_object` (also instruct JSON in a message) [D7].

### 5.3 Streaming and usage [D7, D9]

* Shared parser (§2). `delta.reasoning_content` carries thinking text.
* `finish_reason`: `stop`, `length`, `content_filter`, `tool_calls`, **`insufficient_system_resource`**, **`aborted`**.
* `usage` arrives on the **last chunk** (the one with non-null `finish_reason`). "No separate usage-only chunk is sent." With `include_usage:true`, earlier chunks carry `usage: null`.
* `usage` fields: `prompt_tokens` (= `prompt_cache_hit_tokens` + `prompt_cache_miss_tokens`), `completion_tokens`, `total_tokens`, **`prompt_cache_hit_tokens`**, **`prompt_cache_miss_tokens`**, `prompt_tokens_details.cached_tokens` (= hit), `completion_tokens_details.reasoning_tokens`. No cost field → compute it (mind peak/off-peak by UTC request time).
* `: keep-alive` SSE comments while waiting. "If the request has not started inference after 10 minutes, the server will close the connection" [D9].

### 5.4 Function calling [D4, D5, D7]

* Standard OpenAI tools (§2.4), optional `strict` (beta endpoint).
* **Thinking + tools:** "`reasoning_content` must be fully passed back to the API in all subsequent requests" (even turns without a tool call). Otherwise "the API will return a 400 error" [D5]. → Store `reasoning_content` on the assistant message in the history.
* `tool_choice: "required"` or a named function → 400 in thinking mode [D7]. "Chat Completion API does not support inserting tool calls mid-conversation" [D4].
* **Strict-mode schema limits** [D4]: types object, string, number, integer, boolean, array, enum, anyOf. String `format` only `email`, `hostname`, `ipv4`, `ipv6`, `uuid`. No `minLength`/`maxLength`/`minItems`/`maxItems`. All properties required, `additionalProperties:false`. → Our `publish_articles` schema (`format: "uri"`, `format: "date-time"`, `{"type":"null"}`) **does not fit strict mode**. Use non-strict (default endpoint, `strict` omitted) or a DeepSeek variant without those keywords.

### 5.5 Live News

There is no built-in search, so see §2.6. **Recommendation:** DeepSeek = chat-only provider (no web search toggle), unless a client-side search key exists.

### 5.6 Key test, errors, rate limits

* **Key test (free):** `GET https://api.deepseek.com/user/balance` → `{"is_available": bool, "balance_infos":[{"currency":"CNY"|"USD","total_balance","granted_balance","topped_up_balance"}]}` [D8]. `is_available:false` = no balance. (The auth scheme isn't stated on that page; Bearer as everywhere else [D3].)
* **Errors** [D2]: 400 Invalid Format, **401 Authentication Fails** (wrong key), **402 Insufficient Balance**, 422 Invalid Parameters, **429 Rate Limit Reached**, 500 Server Error, **503 Server Overloaded**.
* **Rate limits** [D9]: concurrency per account (all keys combined): flash 2,500, v4-pro 500. Exceeding → 429.

---

## 6. Perplexity

### 6.1 Status: use the Agent API, not Sonar Chat Completions

* "Sonar Chat Completions support ended on September 27, 2026." "Synchronous and streaming requests keep working": they are being reformulated as Agent API requests, "rolling out gradually by model". "Asynchronous Sonar requests are not reformulated and are no longer supported" [P2, P5]. Billing and response format for reformulated requests are **not stated** [P5].
* July 2026 changelog: "Sonar Chat Completions is now Agent API." `sonar-reasoning` was removed in Dec 2025 [P3].
* → **Build on the Agent API**: `POST https://api.perplexity.ai/v1/agent`; `POST /v1/responses` is an alias "for OpenAI SDK compatibility"; base URL `https://api.perplexity.ai/v1` [P4, P10]. It is **OpenAI Responses-shaped**, so reuse the Responses adapter with the deltas below. "The APIs are not identical, and some defaults and Perplexity extensions differ" [P10].

### 6.2 Endpoint, auth, models

| Item | Value | Source |
|---|---|---|
| Endpoint | `POST https://api.perplexity.ai/v1/agent` | P4, P8 |
| Auth | `Authorization: Bearer $PERPLEXITY_API_KEY` | P4 |
| Model selection | `model: "<provider>/<model>"`, **or** `preset: "fast"\|"low"\|"medium"\|"high"\|"xhigh"`, **or** `models: [...]` (fallback chain ≤ 5, takes precedence over `model`) | P8 |
| Preset mapping (migration) | sonar / sonar-pro → `fast`; sonar-reasoning-pro → `low`; sonar-deep-research → `high` | P5, P6 |

Agent API model prices (AS_OF 2026-10-09, USD per 1M; input / output / cache) [P9, P1]:

| Role | Model | Input | Output | Cache |
|---|---|---|---|---|
| **Top** | `anthropic/claude-opus-5-5` | 4 | 20 | 0.20 |
| **Default** | `google/gemini-3.8-flash` | 0.75 | 3.75 | 0.075 |
| Alt. default | `anthropic/claude-sonnet-5-5` | 2 | 10 | 0.20 |
| **Cheap / fast** | `openai/gpt-6-luna` | 0.10 (≤272k) / 0.20 | 0.50 / 0.75 | 0.01 |
| Perplexity's own | `perplexity/sonar` | 0.25 | 2.50 | 0.0625 |

Note the IDs: Perplexity uses **dashes** for Anthropic versions (`claude-sonnet-5-5`), OpenRouter uses **dots** (`claude-sonnet-5.5`) [P9, R12].
The `fast` preset uses `openai/gpt-6-luna` with priority processing ("2× the model's standard token prices") [P11, snippets]. `max_output_tokens` is **required** for `anthropic/*` models [P8].

**Tool prices** (per invocation) [P1]: `web_search` **$0.0025** ($2.50/1k); with `search_type:"fast"` **$0.001**; `fetch_url` $0.0005; `image_search` $0.0025; `finance_search` and `people_search` $0.005.
Legacy Sonar prices, for reference only [P1]: `sonar` 1 / 1 + request fee $5/$8/$12 per 1k (low/medium/high context); `sonar-pro` 3 / 15 + $6/$10/$14; `sonar-reasoning-pro` 2 / 8; `sonar-deep-research` 2 / 8 + citation tokens $2/1M, reasoning $3/1M, searches $5/1k.

### 6.3 Request ("Frag nach")

```json
{
  "model": "google/gemini-3.8-flash",
  "stream": true,
  "instructions": "Du bist der Nachrichten-Assistent von Morgenbriefing. Antworte immer auf Deutsch …\n\nKONTEXT (Artikel):\n…",
  "input": [
    { "type": "message", "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" }
  ],
  "tools": [
    { "type": "web_search", "search_type": "fast", "max_results": 5,
      "user_location": { "country": "DE", "city": "Berlin" } }
  ],
  "max_steps": 3,
  "max_output_tokens": 2000,
  "language_preference": "de"
}
```

* **`max_steps` gotcha:** "For requests that specify model or models without a preset, the default is 1." At 1, "tools can still run, but the model cannot reason over their output" [P11, snippets]. **Set `max_steps` ≥ 2–3** whenever search is enabled (range 1–100) [P8].
* `web_search` options [P8]: `filters{search_domain_filter (≤20), search_recency_filter ("hour"|"day"|"week"|"month"|"year"), search_after_date_filter, search_before_date_filter, last_updated_after_filter, last_updated_before_filter}` (dates `MM/DD/YYYY`), `max_results` 1–50, `user_location{city,country,latitude,longitude,region}`, `search_type` `"web"`|`"fast"`, `max_tokens`, `max_tokens_per_page`.
* Other fields: `tool_choice` (`none`/`auto`/`required`/object), `previous_response_id`, `reasoning.effort` (`minimal` … `max`), `response_format{type:"json_schema",json_schema{name,schema,strict?}}`, `background`, `store`, `temperature`, `top_p` [P8]. Temperature/top_p are ignored for GPT-5/o1/o3 families [P6].

### 6.4 SSE events [P8, P6]

`response.created`, `response.in_progress`, **`response.completed`**, **`response.failed`**, `response.output_item.added`, **`response.output_item.done`**, **`response.output_text.delta`** (`delta`), `response.output_text.done`, `response.reasoning.started`, `response.reasoning.search_queries`, **`response.reasoning.search_results`**, `response.reasoning.image_search_queries`, `response.reasoning.image_search_results`, `response.reasoning.fetch_url_queries`, `response.reasoning.fetch_url_results`, `response.reasoning.stopped`.
**No `response.function_call_arguments.*` and no annotation events are listed** → read function calls from `response.output_item.done` (item `type:"function_call"`), **UNVERIFIED** live.

### 6.5 Citations [P4, P6]

* Text contains inline markers: numbered `[1]` with the `fast` preset; source-typed `[web:1]` with `low`/`medium`/`high` [P6].
* Sources: output item `{"type":"search_results","queries":[…],"results":[{"id":1,"title","url","snippet","date","last_updated","source":"web"}]}`. Marker `[n]` / `[web:n]` → the result with `id == n` [P4, P6]. `output_text.annotations` exists but is empty in the examples [P4].
* Live: the same results arrive in `response.reasoning.search_results` events (payload shape not shown, **UNVERIFIED**). The final `search_results` item is authoritative.

### 6.6 Usage and cost [P4, P8]

`usage` = `input_tokens`, `output_tokens`, `total_tokens`, `input_tokens_details{cache_creation_input_tokens, cache_read_input_tokens}` (quickstart also shows `cached_tokens`), `output_tokens_details.reasoning_tokens`, **`tool_calls_details{<tool>:{invocation, cost_usd, token_usage}}`**, **`cost{currency:"USD", input_cost, output_cost, total_cost, cache_read_cost?, cache_creation_cost?, tool_calls_cost?, tool_calls_cost_details?}`**. → Perplexity reports the **exact cost in USD**.

### 6.7 Function calling and Live News [P7, P8]

* Definition (flat): `{"type":"function","name":"publish_articles","description":"…","parameters":{…},"strict":true}`.
* Call (output item): `{"id":"fc_…","type":"function_call","call_id":"call_…","name":"…","arguments":"{…}","status":"completed"}`. Some models add `thought_signature`; preserve it when replaying.
* Result (next request's `input`): `{"type":"function_call_output","call_id":"call_…","output":"…"}`. Re-send the original `function_call` item next to its output [P7].
* Mixing: the docs (search-indexed text of the custom-functions/function-calling pages) say built-in tools like `web_search` "are executed server-side" and you "only need to handle function_call items for your custom functions". → **One request** with `tools:[web_search{…search_recency_filter:"day"}, publish_articles]`, `max_steps` ≈ 6–10, `tool_choice:"auto"`.

### 6.8 Key test, errors, rate limits

* **Key test:** no free endpoint (no `/v1/models`) is documented [P9]. The cheapest check is `POST /v1/agent` with `{"model":"openai/gpt-6-luna","input":"ping","max_output_tokens":16}` and no tools (≈ $0.00001).
* **Errors** [P12, snippets]: **401 = invalid/deleted key *or* "an account which ran out of credits"** (no 402) → the UI must say "Key ungültig oder Guthaben leer". 429 = tier rate limit (leaky bucket; some endpoints send `Retry-After`). 5xx = transient, so log `X-Request-ID`. "Read `error.message` … and `error.type` … don't branch on `error.code`" (string, number or null).
* **Rate limits** [P12]: tiers by lifetime purchases ($0/$50/$250/$500/$1,000/$5,000) → Agent API 1/3/8/17/33/33 QPS.
* Async: `background:true` + poll [P6].

---

## 7. OpenRouter

### 7.1 Endpoint, auth, key

| Item | Value | Source |
|---|---|---|
| Base URL | `https://openrouter.ai/api/v1`; chat: `POST /chat/completions` (also `/responses`, `/messages`) | R5, R8 |
| Auth | `Authorization: Bearer <OPENROUTER_API_KEY>` | R7 |
| Attribution headers (optional) | `HTTP-Referer: <site url>` and **`X-OpenRouter-Title: <app name>`** ("for rankings on openrouter.ai"). `X-Title` isn't mentioned on the current page | R7 |
| Key format | `sk-or-v1-…` (docs sample `"label": "sk-or-v1-au7...890"`) | R8 |
| Response header | `X-Generation-Id` | R5 |
| Model IDs | `vendor/model` (e.g. `google/gemini-3.8-flash`). Floating aliases with `~` (e.g. `~anthropic/claude-sonnet-latest`), plus `:free` and `:batch` variants [R12] | R12 |

### 7.2 Models and prices (AS_OF 2026-10-09; from the live `GET /api/v1/models`; USD per 1M; `web_search` = "Price in USD per web search" [R11]) [R12]

| Role | Model ID | Context | Input | Output | Cache read | Native search / call |
|---|---|---|---|---|---|---|
| **Top** | `anthropic/claude-opus-5.5` | 1,000,000 | 4.00 | 20.00 | 0.20 | $0.01 |
| Top (alt) | `openai/gpt-6.1-sol` | 1,050,000 | 2.00 | 10.00 | 0.10 | $0.01 |
| **Default** | `google/gemini-3.8-flash` | 1,048,576 | 0.75 | 3.75 | 0.075 | $0.014 |
| Default (alt) | `anthropic/claude-sonnet-5.5` | 1,000,000 | 2.00 | 10.00 | 0.10 | $0.01 |
| **Cheap / fast** | `openai/gpt-6-luna` | 1,050,000 | 0.10 | 0.50 | 0.01 | $0.01 |
| Cheap (alt) | `anthropic/claude-haiku-5.5` | 1,000,000 | 0.10 | 0.50 | 0.01 | $0.01 |
| Other | `x-ai/grok-4.7` 2/6 ($0.005); `deepseek/deepseek-v4.1-flash` 0.30/1.20; `mistralai/mistral-small-2603` 0.15/0.60; `mistralai/mistral-large-4-0` 0.68/2.09 | | | | | |

All rows above list `tools` in `supported_parameters` [R12]. Filter with `supported_parameters` containing `"tools"` (UI: `openrouter.ai/models?supported_parameters=tools`) [R10].
Fees: buying credits costs 5.5 % ($0.80 minimum) by card, 5 % by crypto. "The base currency is US dollars" [R13].

### 7.3 Request ("Frag nach" with web search)

```http
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer sk-or-v1-…
HTTP-Referer: https://github.com/CedrickGD/Morgenbriefing
X-OpenRouter-Title: Morgenbriefing
Content-Type: application/json
```

```json
{
  "model": "google/gemini-3.8-flash",
  "stream": true,
  "messages": [
    { "role": "system", "content": "Du bist … Antworte auf Deutsch …\n\nKONTEXT:\n…" },
    { "role": "user", "content": "Was bedeutet das für Mieter in Berlin?" }
  ],
  "tools": [
    { "type": "openrouter:web_search",
      "parameters": { "engine": "auto", "max_results": 5, "max_total_results": 10,
                      "search_context_size": "medium",
                      "user_location": { "type": "approximate", "country": "DE", "city": "Berlin", "timezone": "Europe/Berlin" } } }
  ],
  "max_tool_calls": 3,
  "reasoning": { "effort": "low" },
  "provider": { "require_parameters": true }
}
```

* `openrouter:web_search` parameters [R2]: `engine` `auto` (native when available, else Exa) | `native` | `exa` | `firecrawl` | `parallel` | `perplexity`; `mode` (engine-specific); `max_results` (default 5; 1–25, Perplexity 1–20); `max_uses` (forwarded only to Anthropic native); `max_total_results` (default 50 per the schema [R11]); `search_context_size` `low`/`medium`/`high`; `max_characters`; `user_location` (native only); `allowed_domains` / `excluded_domains`; `x_search` (xAI only).
* `max_tool_calls` (default/max 30, all server tools) appears in the docs example [R2, R3] but is **not in the `ChatRequest` schema**. The schema has `stop_server_tools_when`, which "overrides `max_tool_calls`" [R3, R11]. Keep `max_tool_calls`, and if a 400 says unknown field, drop it (**UNVERIFIED**).
* A `web_search_preview` shorthand is accepted and "Automatically converted to openrouter:web_search" [R11].
* `provider.require_parameters` exists in `ProviderPreferences` [R11]. Its routing semantics aren't spelled out on the fetched pages (**UNVERIFIED**). Intent: only route to providers that support every sent parameter (tools).
* "The `tools` parameter must be included in every request" (also on follow-ups with tool results) [R10].
* Deprecated: `plugins:[{"id":"web"}]` and the `:online` suffix → "Use the openrouter:web_search server tool instead" [R2].

### 7.4 Web search pricing [R2, R12]

Native search: passed through from the provider (per model `pricing.web_search`, see §7.2). Exa `instant`/`fast`/`auto` $0.007/request, `deep-lite`/`deep` $0.012, `deep-reasoning` $0.015 (10 results included, +$0.001 per extra result). Parallel `turbo`/`fast` $0.001, `basic`/`advanced` $0.005. Perplexity engine $0.005/request. Firecrawl = BYOK credits. All on top of tokens.

### 7.5 Citations

* Non-streaming: `choices[0].message.annotations[] = {"type":"url_citation","url_citation":{"url","title","content"?,"start_index","end_index"}}` [R1]. Exa excerpts are "surfaced to API callers via `url_citation` annotations" [R2].
* **Streaming location is undocumented.** `ChatStreamDelta` and `ChatAssistantMessage` in the OpenAPI file have **no `annotations` field** [R11]. → Parse `delta.annotations` *defensively* if present, and fall back to markdown links in the text (the default search prompt asks the model to cite "as markdown links named after their domain" [R1]). **UNVERIFIED**, test once.

### 7.6 Streaming specifics [R5, R6, R11]

* `: OPENROUTER PROCESSING` comments.
* **Final usage chunk**: one choice, empty `delta`, repeats `finish_reason` → don't treat it as a second finish.
* **Mid-stream error** (HTTP stays 200): `{"id","object":"chat.completion.chunk","model","provider","error":{"code":429,"message":"…","metadata":{"error_type":"rate_limit_exceeded","provider_code"?}},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}`. "The error can be the only event", so treat a 200 with an error chunk and no content as a failure.
* Cancel = abort the connection; billing stops only for supported providers [R5].

### 7.7 Usage and cost [R4, R11]

Always included ("`usage: { include: true }` and `stream_options: { include_usage: true }` are deprecated and have no effect") [R4].
`usage` = `prompt_tokens`, `completion_tokens`, `total_tokens`, **`cost`** (credits = USD), `is_byok`, `cost_details{upstream_inference_cost, upstream_inference_prompt_cost, upstream_inference_completions_cost}`, `prompt_tokens_details{cached_tokens, cache_write_tokens, audio_tokens, video_tokens}`, `completion_tokens_details{reasoning_tokens, …}`, **`server_tool_use_details{web_search_requests, tool_calls_requested, tool_calls_executed}`** [R11]. The prose docs say `usage.server_tool_use.web_search_requests` [R3], so parse both names. "Do not sum" `web_search_requests` and `tool_calls_requested` [R11].

### 7.8 Live News (one request) [R3, R10]

`tools: [ {"type":"openrouter:web_search","parameters":{…}}, {"type":"function","function":{"name":"publish_articles","description":"…","parameters":{…}}} ]`.
"OpenRouter intercepts the tool call, executes it server-side, and returns the result to the model … your application handles the user-defined tool calls as usual" [R3]. The response ends with `finish_reason:"tool_calls"` carrying `publish_articles`. Whether server-tool calls are ever visible in `delta.tool_calls` is not stated → **ignore tool calls whose name isn't ours**.

### 7.9 Key test, errors, rate limits

* **Key test (free):** `GET https://openrouter.ai/api/v1/key` → `data{label, limit, limit_remaining, limit_reset, usage, usage_daily/weekly/monthly, is_free_tier, byok_usage…, free_model_daily_requests{limit,remaining,used}, expires_at, …}`. `rate_limit` is "deprecated and safe to ignore" [R8].
* **Errors** [R6]: body `{"error":{"code":<=HTTP status>,"message","metadata?"}}`. 400 bad params; **401** invalid/disabled key or expired OAuth; **402** insufficient credits (a 402 **with `Retry-After`** = in-flight budget full → wait and retry; without it → top up) [R6, R9]; **403** permission, guardrail or **moderation flag** (`metadata.reasons`, `flagged_input`, `provider_name`, `model_slug`); 408 timeout; **429** rate limited (platform 429s carry `X-RateLimit-Limit/Remaining/Reset`; can also be upstream) [R9]; **502** model down / invalid upstream response; **503** no provider meets the routing requirements. `Retry-After` may appear on 429, 503 and some 402s [R6].
* **Rate limits** [R9]: `:free` models 20 req/min, and 50/day (< 10 credits ever purchased) or 1,000/day. Paid models: "no platform-level request cap". A negative balance → 402 even on free models.

---

## 8. Self-hosted (Ollama, LM Studio, vLLM)

| | Ollama | LM Studio | vLLM |
|---|---|---|---|
| Base URL | `http://<host>:11434/v1/` [L1] | `http://<host>:1234/v1` [L3] | `http://<host>:8000/v1` (docs' client example) [L7] |
| Auth | Key required by clients but "Ollama ignores it" (local). Ollama Cloud: `https://ollama.com/v1` + `OLLAMA_API_KEY` [L1] | Off by default. API tokens (LM Studio ≥ 0.4.0), `Authorization: Bearer $LM_API_TOKEN` [L5] | `--api-key` or `VLLM_API_KEY` "only authenticates requests to endpoints under the `/v1`" [L7] |
| Endpoints | `/v1/chat/completions`, `/v1/models`, `/v1/embeddings`, `/v1/completions`, `/v1/responses` (stateless, ≥ v0.13.3) [L1] | `/v1/models`, `/v1/chat/completions`, `/v1/responses`, `/v1/embeddings`, `/v1/completions` [L3] | `/v1/chat/completions`, `/v1/responses`, `/v1/models`, … [L7] |
| Tools | Supported; **`tool_choice` unsupported** [L1] | Native support for some families, "default" prompt-injected support for all others [L4] | Server needs `--enable-auto-tool-choice` + `--tool-call-parser <hermes\|llama3_json\|mistral\|pythonic\|qwen3_xml\|…>` [L6] |
| Streaming tool calls | not explicitly documented [L1] | chunked by `index` (id + name first, then args) [L4] | each call emitted whole once parsed; unparsable calls are dropped [L6] |
| Usage | `stream_options.include_usage` [L1] | not documented | not documented |
| Web search | None built in. Separate Ollama web-search REST API as a client tool (§2.6) [L2] | none | none |
| Key test | `GET /v1/models` | `GET /v1/models` | `GET /v1/models` |
| Price | $0 (Ollama Cloud pricing not researched) | $0 | $0 |

Android notes (platform knowledge, **not from the provider docs above**): a phone can't reach the PC via `localhost`; use the LAN IP, and the server must listen on the LAN interface. The Android emulator reaches the host at `10.0.2.2`. Plain `http://` needs a `network_security_config` cleartext exception for that host (blocked by default since targetSdk 28).

---

## 9. Cross-provider error mapping (for `AiErrors`-style classification)

| Category (UI) | Mistral | xAI | DeepSeek | Perplexity | OpenRouter | Self-hosted |
|---|---|---|---|---|---|---|
| **Key invalid** | 401 (`type: authentication_error`) | 401 | 401 | 401 (*also* "out of credits"; check message) | 401 | 401 only if a token is configured; connection refused = server down |
| **No credit / billing** | 402 (no payment method); 429 when the workspace spend limit is hit (snippet) | not documented; on 403 check `GET /v1/api-key` → `team_blocked` | **402 Insufficient Balance** | 401 (see above) | **402** (with `Retry-After` = transient) | – |
| **Forbidden / moderation** | 403 | 403 | – | – | **403** (`metadata.reasons`) | – |
| **Rate limit** | 429 (`rate_limit_error`) | 429 | 429 (concurrency) | 429 (`Retry-After` sometimes) | 429 (`X-RateLimit-*`, `Retry-After`) | – |
| **Overloaded / upstream down** | 500/502/503/504 | 5xx (undocumented) | **503 Server Overloaded**, 500; mid-stream `finish_reason: insufficient_system_resource` | 5xx | **502**, **503** (no provider), 408 | 5xx |
| **Bad request** | 400, 422 | 400, 422, 415 | 400, 422 (thinking+tools without `reasoning_content` → 400) | 400 | 400 | 400 |
| **Mid-stream error** | `finish_reason:"error"` (chat); `conversation.response.error {message, code}` | Responses `response.failed` / `error` events (OpenAI grammar, **UNVERIFIED** for xAI) | `finish_reason` `insufficient_system_resource` / `aborted` | `response.failed` | chunk with top-level `error` + `finish_reason:"error"` | – |

Retry policy: 429/5xx with exponential backoff, honouring `Retry-After`. Never auto-replay after output has been streamed (as in `openai.md`).

---

## 10. Open questions (not answerable from the docs on 2026-10-09)

1. **Mistral `web_search` / `web_search_premium` per-call price**: not on https://docs.mistral.ai/inference/pricing or https://mistral.ai/pricing ("tool APIs are priced per call"). Check the Admin › Usage page after one test call.
2. Mistral Conversations: is mixing `web_search` + `function` in one request really supported (the schema allows it), and does `store:false` + full re-send work for the correction loop?
3. Mistral Chat Completions: which chunk carries `usage`, and is there a cached-token field?
4. xAI: is `search_parameters` (Live Search) still functional or ignored? Raw REST placement of `allowed_domains` (top-level vs `filters`)? HTTP status for an exhausted prepaid balance? Full Responses SSE event list?
5. Perplexity: does the Agent API stream function calls only via `response.output_item.done`? What is the payload of `response.reasoning.search_results`? Are reformulated legacy Sonar calls billed at Sonar or Agent prices?
6. OpenRouter: do `url_citation` annotations arrive in streaming deltas or only non-streaming? Is top-level `max_tool_calls` accepted on Chat Completions (it's missing from the schema)? Are server-tool calls ever visible as `delta.tool_calls`?
7. DeepSeek: is a Responses-API web search planned? (Nothing documented.)
8. Ollama web search API quotas and pricing.
