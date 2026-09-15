# AI Dialogue Integration Bridge

## Purpose

This project is the Minecraft NeoForge mod side of the Mythic TRPG system. AI dialogue execution is expected to be developed on another PC, where RisuAI and a local LLM will be used and tested separately.

The goal is to keep this mod portable to that other PC and make it compatible with the external dialogue system without coupling core gameplay rules to AI output.

## Current Project Responsibility

This mod owns authoritative gameplay state and rules:

- God definitions, unlock state, appearance checks, and identity presentation.
- Player myth data, knowledge, encounters, item history, and custom gameplay counters.
- Gameplay observations and sampling boundaries.
- Interaction selection, start validation, runtime cooldowns, encounter commit, and dialogue HUD delivery.

The mod must not depend on a specific AI provider, RisuAI runtime, local LLM model, or prompt implementation.

## External Dialogue System Responsibility

The other PC's RisuAI/local LLM system owns dialogue generation:

- Prompt construction.
- Character voice and style.
- Local LLM provider configuration.
- RisuAI-specific memory, lorebook, group chat, and persona behavior.
- Provider retries, model choice, and local inference performance tuning.

That system may be developed independently and later connected through a narrow request/response boundary.

## Integration Principle

AI may generate dialogue text, but it must not decide gameplay legality.

AI must not decide:

- Whether a God is unlocked.
- Whether a God can appear.
- Which God is selected by gameplay rules.
- Whether an encounter is committed.
- Player knowledge, affinity, rewards, quests, or world state mutation.

The Minecraft server remains authoritative. AI responses are content suggestions only.

## Recommended Boundary

The bridge between this mod and the external dialogue system should be a plain data contract, not Minecraft live objects.

Do not send or store:

- `ServerPlayer`
- `Entity`
- `Level`
- `ItemStack`
- `DamageSource`
- Mutable maps of arbitrary data

Use snapshot values only:

- `UUID`
- `ResourceLocation`
- plain strings
- bounded text snippets
- primitive numbers
- small structured lists

## Dialogue Request Shape

The future request should be speaker-based rather than God-only, so other mods can later reuse it.

```text
AiDialogueRequest
- requestId
- speakerId
- speakerKind
- speakerDisplayName
- audiencePlayerId
- playerDisplayName
- userMessage
- contextSnippets
- recentGameplayObservations
- maxResponseCharacters
- timeoutMillis
```

Suggested speaker kinds:

```text
GOD
NPC
MOB
ITEM
LOCATION
OTHER_MOD_CHARACTER
SYSTEM_TEST
```

For the current Mythic TRPG path, `speakerKind=GOD` and `speakerId=<god ResourceLocation>` are enough.

## Dialogue Response Shape

```text
AiDialogueResponse
- requestId
- status
- speakerId
- text
- diagnostics
```

Suggested statuses:

```text
SUCCESS
NO_CONTENT
TIMEOUT
PROVIDER_DISABLED
PROVIDER_FAILED
INVALID_REQUEST
```

The response must not contain commands that mutate game state. If the AI suggests an action, it is treated as text only unless a future server-side rule explicitly interprets it.

## Initial Test Modes

Use isolated test modes before any production interaction integration.

### AI-0a: Fake Provider

Purpose:

- Verify command flow, request shape, response limits, and failure paths.
- No network and no local LLM required.

Output:

- Command feedback or log only.
- Optional HUD preview later.

No gameplay state mutation.

### AI-0b: External Local Provider Dry Run

Purpose:

- Send a bounded request to the other PC's RisuAI/local LLM bridge or a local HTTP provider.
- Inspect dialogue quality and latency.

Output:

- Command feedback or log only at first.

No `InteractionOrchestrator` call.

### AI-0c: HUD Preview

Purpose:

- Display generated text through the existing dialogue HUD as a preview.

Still no encounter commit, knowledge mutation, unlock mutation, or cooldown mutation.

## Transport Options

The transport should be replaceable.

Recommended options:

- Local HTTP endpoint on the Minecraft server machine.
- LAN HTTP endpoint to the other PC.
- File-based exchange for early manual testing.
- Later, a plugin or socket bridge if RisuAI requires it.

The mod should depend on an interface, not a concrete transport.

```text
AiDialogueProvider
- prepare/check availability
- request dialogue asynchronously
- return AiDialogueResponse
```

Possible implementations:

```text
FakeAiDialogueProvider
LocalHttpDialogueProvider
RisuBridgeDialogueProvider
OpenAiDialogueProvider
DisabledAiDialogueProvider
```

## Configuration Rules

Do not hardcode provider secrets or machine-specific addresses in source code.

Use one of:

- server config
- environment variables
- local ignored config file

Configuration should support:

- provider enabled/disabled
- endpoint URL
- timeout
- max prompt characters
- max response characters
- logging level

Never log API keys, bearer tokens, or full private prompts by default.

## Handoff Requirements

When passing the mod to the other PC, include:

- Git commit hash.
- Build artifact or source branch.
- Java and NeoForge versions.
- Expected request/response schema.
- Current unsupported features.
- Which commands or API endpoints are safe to test.

The other PC dialogue system should report back:

- Provider endpoint shape.
- Required config values.
- Example request.
- Example response.
- Timeout behavior.
- Failure response behavior.
- Whether responses are streaming or non-streaming.

## Current Development Rule

Until the external dialogue bridge is ready, continue building the Minecraft mod with provider-agnostic boundaries.

For PHASE 4 work:

- Continue gameplay statistics, observations, sampling, and promotion rules without assuming an AI provider.
- Do not embed RisuAI logic in core gameplay packages.
- Do not let AI output bypass server validation.

For AI experiments:

- Keep them in an isolated AI phase.
- Prefer `FakeAiDialogueProvider` first.
- Only add local/Risu provider after the request/response boundary is stable.

## Suggested Next Milestones

```text
PHASE 4-A-2-2b
- threshold crossing observation emission
- no AI dependency

PHASE AI-0a
- fake provider
- admin dry-run command
- no game state mutation

PHASE AI-0b
- external local/Risu bridge dry-run
- command feedback only

PHASE AI-0c
- optional HUD preview

PHASE AI-1
- InteractionContentPreparer prototype
- still server-rule-authoritative
```

