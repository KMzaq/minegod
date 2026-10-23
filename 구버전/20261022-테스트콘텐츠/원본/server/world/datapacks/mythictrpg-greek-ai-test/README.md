# MythicTRPG Greek AI Test Datapack

This development datapack provides the 135 minimal Greek God Definitions used to test the imported AI dialogue content.

It deliberately remains outside the mod's bundled resources.  The MythicTRPG base dataset and automated GameTests therefore keep their original God Definition set.

## Development-world installation

The same datapack is already copied to `run/world/datapacks/mythictrpg-greek-ai-test` for the Gradle development world.

1. Start or re-open the development world.
2. Run `/reload`.
3. Run `/datapack list enabled` and confirm `file/mythictrpg-greek-ai-test` is enabled.  If it is disabled, run `/datapack enable "file/mythictrpg-greek-ai-test"` followed by `/reload`.
4. Run `/mythadmin gods` to see all loaded definitions.
5. Use any listed `mythictrpg:greek_<affiliation>_<name>` ID to begin an AI dialogue.

These definitions intentionally include only identity, display name, origin, faction, and categories.  They are unlocked by default so every AI content pack can be tested.  Replace each file with the gameplay team's final God Definition when its mechanics, unlock conditions, appearance, and encounter rules are ready.
