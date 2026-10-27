# AI provider compatibility — 1332

The previous common request body obscured provider differences, and `has("tool_calls")` rejected normal Cerebras messages containing `tool_calls: null`. All provider errors were then replaced with a generic failure. New adapters use each provider's documented request/authentication envelope and validate only completed text answers before the existing catalog-ID/action validation.

| Provider | Generation API | Authentication | Token limit |
| --- | --- | --- | --- |
| Google AI Studio | `/v1beta/models/{model}:generateContent` | `x-goog-api-key` | `generationConfig.maxOutputTokens` |
| OpenAI | `/v1/responses` | Bearer | `max_output_tokens` |
| xAI | `/v1/responses` | Bearer | `max_output_tokens` |
| Anthropic | `/v1/messages` | `x-api-key`, `anthropic-version` | `max_tokens` |
| Groq | `/openai/v1/chat/completions` | Bearer | `max_completion_tokens` |
| Cerebras | `/v1/chat/completions` | Bearer | `max_completion_tokens` |
| Hugging Face | `/v1/chat/completions` | Bearer | `max_tokens` |

Responses requests set `store=false`. Optional model-specific sampling/reasoning parameters are omitted. Gemini, OpenAI, Groq and Cerebras use their documented JSON output modes. Native reasoning blocks and complete leading HF `<think>` envelopes are separated from the answer. Null/empty tool-call fields are allowed; actual tool requests, refusals and truncated outputs never become music actions. Existing key storage and explicit review of library actions remain in place.

Connections can be edited while keeping an existing encrypted key. Model lists come from each provider's models endpoint (first page for paginated providers, up to 200 displayed entries); manual entry remains available. Lists may contain non-text models, so the UI asks the user to select a text model and test it. A connection test uses the same transport and catalog validation with one fictitious track, without reading the music library. It is an explicit billable API request. Model IDs already saved by the user are never silently replaced.

Authentication, permission, missing model, invalid request, quota, server, timeout, truncated and invalid-format errors have separate guidance. HTTP Retry-After seconds and dates are supported. Disabling fallback also prevents switching to another account while the primary account is in cooldown. The router preserves the last failure during cooldown; editing or successfully testing a connection clears its cooldown. Requests remain cancellable, with a 65-second per-call timeout and a 180-second overall fallback budget.

## Validation and limits

JVM tests exercise all seven authentication/request formats, native response fixtures, null tool fields, content arrays, thinking blocks, malformed/truncated/refused/tool results, model lists, HTTP error handling, Retry-After and cancellation. Local MockWebServer requests exercise the real OkHttp transport. An Android Compose test edits a model without replacing its encrypted key and verifies cancelling a key replacement.

No live paid provider API call was made during development: no user provider keys are available in this environment. These tests establish wire-format handling, not the availability of any particular model on a user's account. Use the in-app model list and connection test to check account access.

## Official references reviewed 2026-10-26

- [Gemini GenerateContent](https://ai.google.dev/api/generate-content) and [models](https://ai.google.dev/gemini-api/docs/models)
- [OpenAI Responses migration](https://developers.openai.com/api/docs/guides/migrate-to-responses)
- [Claude Messages](https://platform.claude.com/docs/en/api/messages/create) and [models](https://platform.claude.com/docs/en/models/overview)
- [Groq API reference](https://console.groq.com/docs/api-reference)
- [Cerebras Chat Completions](https://inference-docs.cerebras.ai/api-reference/chat-completions)
- [Hugging Face Chat Completion](https://huggingface.co/docs/inference-providers/tasks/chat-completion)
- [xAI text generation](https://docs.x.ai/developers/model-capabilities/text/generate-text)
