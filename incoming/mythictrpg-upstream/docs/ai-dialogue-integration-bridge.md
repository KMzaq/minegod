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

## Current Functional Baseline

```text
Functional commit: 4e20737fea42160c52c52cf8b95588f0fbf1971b
Phase: 4-B-1, Demeter wheat harvest binding
Previously verified GameTests: 158/158
Minecraft: 1.21.1
NeoForge: 21.1.248
Java: 21.0.11
```

A documentation-only commit created later can have a different Git HEAD; the functional baseline above still identifies the verified gameplay implementation.

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

## Existing Production Integration API

The production resolver must be installed through the existing configure-once router:

```java
InteractionContentPreparerResolverRouter.INSTANCE
        .configureProductionResolver(resolver);
```

The relevant existing contracts are:

```text
com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolverRouter
com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolver
com.sande.mythictrpg.interaction.spontaneous.ContentPreparerResolution
com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionSubmissionService
com.sande.mythictrpg.interaction.content.InteractionContentPreparer
com.sande.mythictrpg.interaction.content.ContentPreparationRequest
com.sande.mythictrpg.interaction.content.PreparationResult
com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator
```

`InteractionContentPreparerResolver.resolve(signal)` returns `ContentPreparerResolution.available(preparer)` or `ContentPreparerResolution.unavailable()`. Production configuration accepts the first resolver and rejects a different resolver after configuration. `setResolverForTesting(...)`, `clearResolverOverrideForTesting()` and `resetForTesting()` are test-only controls and must not be used to register production providers.

Resolution happens before the final interaction plan exists. A resolver may inspect the signal type, mode and typed payload, but must not assume that a final God has already been selected. The authoritative selected God and plan are available later through `ContentPreparationRequest.plan()`; `plan.participants().primaryGodId()` identifies the primary speaker.

`InteractionContentPreparer.prepare(ContentPreparationRequest)` returns `CompletionStage<PreparationResult>`. The provider must perform network or local-LLM work asynchronously and must never block the Minecraft server thread. Preparation results are `PREPARED`, `NO_CONTENT` or `FAILED`; successful content still goes through existing server validation and interaction-start checks.

`PreparedInteractionContent` currently allows at most 16 turns. A turn must belong to an approved interaction participant, the primary God must speak, and dialogue text is limited by the existing server-side validation boundary, currently 1,024 code points per dialogue payload.

## Current Production Execution Flow

The existing Demeter production path is:

```text
mature minecraft:wheat harvest
-> GameplayObservation: mythictrpg:mature_crop_harvested
-> GameplayIngressService
-> gameplay promotion: mythictrpg:demeter_wheat_harvest
-> GameplayActionPayload
-> gameplay signal: mythictrpg:demeter_harvest
-> GameplaySignalSinkRouter
-> GameplaySpontaneousInteractionSink
-> SpontaneousInteractionSubmissionService
-> resolver.resolve(signal)
-> selected InteractionContentPreparer
-> InteractionOrchestrator planning
-> preparer.prepare(ContentPreparationRequest)
-> content/start validation
-> server-authoritative interaction start
-> Dialogue HUD
```

No production resolver is currently configured, so real gameplay stops at:

```text
resolver.resolve(signal)
-> UNAVAILABLE
```

Consequently, no content preparation, encounter commit, interaction cooldown or Dialogue HUD delivery occurs. The Demeter promotion attempt cooldown is still consumed before the sink call, even when the resolver is unavailable.

## Runtime, Cooldown and Stale Response Rules

- The Demeter promotion attempt cooldown is 1,200 game ticks.
- The promotion attempt cooldown is separate from the spontaneous interaction cooldown.
- The spontaneous interaction cooldown is applied only after a successful server-authoritative interaction commit; its current runtime value is 200 ticks.
- A spontaneous in-flight permit has a default timeout of 1,200 game ticks.
- Each player can own only one in-flight permit.
- Each server can own at most 1,024 in-flight permits.
- Player logout and server stop discard the relevant in-flight state.
- A response arriving after timeout, logout, server stop or permit replacement is stale and must not start an interaction.
- Provider failures, cancellation and malformed responses must not bypass `GuardedInteractionContentPreparer`, existing preparation validation or server-thread continuation.

## Existing Administrator Preview

```text
/mythadmin interaction dry-run god <player> <god>
```

This command performs read-only candidate planning and provider availability resolution. It does not call `prepare`, commit encounter/knowledge changes, consume cooldowns, start an interaction or display a dialogue HUD.

Its signal is `mythictrpg:explicit_god_call`, while the Demeter gameplay signal is `mythictrpg:demeter_harvest`. A resolver that recognizes only `demeter_harvest` can therefore leave the administrator dry-run at `PROVIDER_UNAVAILABLE`; do not treat the two signal paths as interchangeable.

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

Use isolated test modes before connecting any real AI provider. These AI phases are future work on the other PC; none is implemented in the current repository.

### AI-0a: Fake Provider

Purpose:

- Verify provider availability, request shape, response limits, and failure paths.
- No network and no local LLM required.

Output:

- Command feedback or log only.
- Reuse or extend a safe read-only preview without invoking production interaction start.

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
- The full Git repository and history; a built JAR is optional reference material, not a substitute for source control history.
- Java and NeoForge versions.
- The existing resolver/preparer contracts and the proposed external request/response schema.
- Current unsupported features.
- Which commands or API endpoints are safe to test.

The complete Korean onboarding and machine-local JDK instructions are in `docs/other-pc-ai-handoff.md`.

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
Completed gameplay baseline
- production Demeter mature wheat harvest binding
- provider-agnostic spontaneous submission and read-only preview

PHASE AI-0a
- deterministic fake provider
- safe admin dry-run extension or isolated provider preview
- no game state mutation

PHASE AI-0b
- external local/Risu bridge dry-run
- command feedback only

PHASE AI-0c
- optional HUD preview

PHASE AI-1
- production resolver and InteractionContentPreparer integration
- mature wheat -> Demeter -> validated dialogue scenario
- still server-rule-authoritative
```
