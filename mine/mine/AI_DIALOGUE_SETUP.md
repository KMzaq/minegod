# MythicTRPG direct Ollama dialogue setup

The mod talks directly to a server-local Ollama `/api/chat` endpoint. RisuAI, a browser window,
a character-card import, and the old loopback bridge are not part of this path.

## One-time Ollama setup

From the MythicTRPG project directory, register the prepared GGUF if `qwen3-14b` is not already
shown by `ollama list`:

```powershell
& 'C:\Users\ADMIN\AppData\Local\Programs\Ollama\ollama.exe' create qwen3-14b -f .\ai-assets\Modelfile.qwen3-14b
```

Start Ollama, then confirm the local API and model:

```powershell
ollama list
Invoke-RestMethod http://127.0.0.1:11434/api/tags
```

The mod sends `think: false`, `stream: false`, and requests JSON from Ollama. No API key is used
or stored.

## First server start

At startup, the server creates these editable files:

```text
config/mythictrpg/ai-dialogue.json
config/mythictrpg/ai-personas.json
config/mythictrpg/npc-agents.json
config/mythictrpg/character-tags.json
config/mythictrpg/npc-character-tags.json
config/mythictrpg/character-style-mappings.json
config/mythictrpg/reaction-guidelines.json
```

`ai-dialogue.json` contains the Ollama endpoint, model name, response and context limits. Its
default endpoint is `http://127.0.0.1:11434/api/chat` and its default model is `qwen3-14b`.

`ai-personas.json` contains the God persona, background, knowledge, and style examples. It is the
direct replacement for the old character card. An example may start with relationship metadata,
for example `[R_RIVAL]`, `[R_FRIENDLY]`, or `[R_CLOSE]`; those tags are only used to select a
fitting prompt example and are not saved as the relationship itself.

`npc-agents.json` is the declaration of each NPC Agent. It holds the stable ID/name, persona
reference, 0.0–1.0 personality scores, values, likes/dislikes, `P_*` speech styles, knowledge
permissions, global emotion, capabilities, and restrictions. Add a new agent by adding one JSON
entry; no Java class is needed for its personality. `globalEmotion` is the NPC-wide baseline.
The per-player current emotion remains separate in relationship data and is read at runtime, so it
does not overwrite that baseline. Capabilities and restrictions are descriptive boundaries only:
they do not give the AI permission to mutate Minecraft.

`character-tags.json` is the standardized source-tag registry. `npc-character-tags.json` stores
each NPC's complete raw tag list and an optional explicit classification. Explicit categories take
priority; unspecified categories are derived once at load time from the raw list. Character tags
are distinct from `P_*` example-style tags; `character-style-mappings.json` is the intentionally
narrow mapping between those two layers.

`reaction-guidelines.json` stores advisory reaction considerations such as `LOW_HEALTH` and
`UNKNOWN_INFORMATION`. These guidelines are not executable rules and cannot create a quest, give
an item, or change a relationship. They will be combined with selected examples in a later prompt
assembly phase.

After editing any of these AI configuration files, run:

```text
/mythai reload
```

## In-game smoke test

With the mod installed on the NeoForge client and server, an operator can run:

```text
/mythai start mythictrpg:lubras
```

Ordinary chat is private input to the active conversation. Prefix a message with `!` to use normal
public chat. Use `/mythai status` to inspect the session and `/mythai leave` to leave it.

For operator-only diagnostics, use:

```text
/mythai debug mythictrpg:lubras
```

It reports NPC agent identity/global emotion/capability restrictions, raw/classified NPC tags,
supplied situation signals, selected guideline IDs with scores and reasons, example style tags,
and selected example IDs. It does not call the LLM and is never shown to ordinary players.

## Data boundaries and logs

Each session is independent and can contain multiple players and divine NPCs. A participant is
`ACTIVE`, `LISTENER`, or `OUTSIDE`; only non-`OUTSIDE` participants receive the dialogue context.

The mod writes a UTF-8 transcript copy for every participating player under:

```text
mythictrpg-dialogue-logs/<player-name>_<uuid>/<date>_<time>_<god-name>.txt
```

This path is inside the running server's game directory. The log contains player lines, divine
lines, and any AI action proposal with its validation result.

The AI may produce dialogue and non-binding `quest`, `reward`, `action`, `relationship`, or
`audience` proposals. It has no gameplay mutation API: it cannot grant items, register quests,
change relationship data, spawn entities, or alter the world. A separate game-layer validator must
accept and execute any proposal.

Relationship data is stored separately from the existing RPG affinity/progression value. The AI
relationship record contains `affinity`, `trust`, `respect` (each `-100..100`) and `caution`
(`0..100`); emotion intensity data is stored independently. Derived tags such as `R_RIVAL` are a
replaceable conversation-style policy, never the persisted source of truth.
