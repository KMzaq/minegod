# RisuAI integration patch

Run `apply-risu-patch.ps1` from this folder. It adds one loopback-only worker that polls the
local Minecraft queue after RisuAI finishes loading its database.

The worker resolves a `risuCharacterId` as an exact Risu `chaId` first. For the initial
멸룡루브라스 setup, it also accepts one unique Risu character display name. This is why the
provided character card must be imported only once.

The generated `minecraft-session:<UUID>` group chats preserve ordinary conversation history and
the selected character's card/lore. Minecraft world facts are added only as a temporary author
note for the current generation.
