# Gen-e2 context map

This project applies the Gen-e2 principles described by PALO IT as a compact AI-first product development loop: shared context, short prompts, rapid feedback, and evidence kept with the product.

## Shared context

| Context layer | Repository evidence |
|---|---|
| Product intent | `README.md`, demo journey and API contract |
| Technical decisions | `DECISIONS.md` |
| Domain knowledge | `src/main/resources/corpus/*.json` |
| Generation contract | `src/main/resources/prompts/grounded-answer.txt` |
| Quality expectations | `src/main/resources/eval/golden-set.json` |
| Runtime evidence | `logs/*.jsonl`, `reports/eval.*` |
| User experience | `frontend/src/app/app.ts` and `app.html` |

## Feedback loop

1. **Frame the intent**: answer internal questions with evidence, never invent context, and enforce document permissions.
2. **Generate**: retrieve permitted chunks and generate with the selected provider (`local` or Ollama).
3. **Observe**: persist the trace ID, provider, retrieval scores, citations, guardrail status and timings.
4. **Evaluate**: run the golden set and quality gate.
5. **Enrich context**: update decisions, corpus, tests or UI from the observed gap, then repeat.

The Angular telemetry panel makes the loop visible during the live debrief. The backend remains the source of truth for policies and evaluation, while the UI exposes enough evidence to challenge a response instead of treating the model as authoritative.

## AI-first guardrails

- AI generation is bounded by retrieved context and a low-temperature provider configuration.
- A missing or weak context produces `insufficient_context`.
- RBAC filtering happens before retrieval and generation.
- Prompt injection, secret exfiltration and sensitive values are blocked or redacted.
- Automated checks are deterministic and versioned; human review remains the next step for production promotion.
