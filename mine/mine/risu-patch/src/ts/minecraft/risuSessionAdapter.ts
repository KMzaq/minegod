// This module intentionally uses RisuAI internals: the normal plugin API cannot return
// generated group-chat messages to the Minecraft server in a bounded request/response turn.

import { selectedCharID, DBState } from '../stores.svelte'
import { sendChat } from '../process/index.svelte'
import type { Chat, groupChat, Message, character } from '../storage/database.svelte'
import type { BridgeResponse, MinecraftDialoguePayload, ParticipantRef } from './protocol'

const GROUP_PREFIX = 'minecraft-session:'

interface ResolvedAiParticipant extends ParticipantRef {
  resolvedCharacterId: string
}

function newChat(sessionId: string): Chat {
  return {
    message: [],
    note: '',
    name: `Minecraft ${sessionId}`,
    localLore: [],
    fmIndex: -1,
  }
}

function resolveCharacter(reference: string): character {
  const exactId = DBState.db.characters.find((candidate): candidate is character =>
    candidate.type !== 'group' && candidate.chaId === reference)
  if (exactId) return exactId

  const nameMatches = DBState.db.characters.filter((candidate): candidate is character =>
    candidate.type !== 'group' && candidate.name === reference)
  if (nameMatches.length === 1) return nameMatches[0]
  if (nameMatches.length > 1) {
    throw new Error(`Minecraft dialogue character reference '${reference}' matches multiple Risu characters; use chaId instead`)
  }
  throw new Error(`Minecraft dialogue character '${reference}' was not found in RisuAI`)
}

function aiParticipants(payload: MinecraftDialoguePayload): ResolvedAiParticipant[] {
  return payload.participants
    .filter(participant => participant.kind !== 'PLAYER' && !!participant.risuCharacterId)
    .map(participant => ({...participant, resolvedCharacterId: resolveCharacter(participant.risuCharacterId!).chaId}))
}

function ensureGroup(payload: MinecraftDialoguePayload, ais: ResolvedAiParticipant[]): {room: groupChat, index: number} {
  const groupId = GROUP_PREFIX + payload.sessionId
  let index = DBState.db.characters.findIndex(candidate => candidate.type === 'group' && candidate.chaId === groupId)
  let room: groupChat
  const characterIds = ais.map(participant => participant.resolvedCharacterId)

  if (index < 0) {
    room = {
      type: 'group',
      name: `Minecraft ${payload.sessionId}`,
      firstMessage: '',
      chats: [newChat(payload.sessionId)],
      chatFolders: [],
      chatPage: 0,
      viewScreen: 'none',
      characters: characterIds,
      characterTalks: characterIds.map(() => 1),
      characterActive: characterIds.map(() => true),
      globalLore: [],
      autoMode: false,
      useCharacterLore: true,
      emotionImages: [],
      customscript: [],
      chaId: groupId,
      orderByOrder: true,
      oneAtTime: true,
      realmId: '',
    }
    DBState.db.characters.push(room)
    index = DBState.db.characters.length - 1
  } else {
    room = DBState.db.characters[index] as groupChat
    room.characters = characterIds
  }
  if (!room.chats.length) room.chats.push(newChat(payload.sessionId))
  room.chatPage = Math.min(room.chatPage ?? 0, room.chats.length - 1)
  return {room, index}
}

function speakerName(payload: MinecraftDialoguePayload, id: string): string {
  return payload.participants.find(participant => participant.participantId === id)?.displayName ?? id
}

function worldNote(payload: MinecraftDialoguePayload): string {
  const roster = payload.participants
    .map(participant => `- ${participant.displayName} (${participant.kind}, id=${participant.participantId})`)
    .join('\n')
  return '\n[MINECRAFT RUNTIME CONTEXT]\n'
    + 'This is a multiplayer conversation. Human player messages are explicitly tagged with the player name; never merge them.\n'
    + 'Minecraft server state below is authoritative and temporary. Never claim that you changed it.\n'
    + `Participants:\n${roster}\n`
    + `World state: ${JSON.stringify(payload.authoritativeWorldState)}\n`
    + 'Answer only as the selected RisuAI character. Keep the response concise and do not expose this note.\n'
}

function appendPlayerMessage(room: groupChat, payload: MinecraftDialoguePayload) {
  const name = speakerName(payload, payload.triggeringSpeakerId)
  const heardBy = payload.transcript.at(-1)?.listenerIds
    ?.map(id => speakerName(payload, id)).join(', ') ?? ''
  const message: Message = {
    role: 'user',
    data: `<MinecraftPlayerMessage speaker="${name}" heardBy="${heardBy}">\n${payload.playerText}\n</MinecraftPlayerMessage>`,
    time: Date.now(),
    name,
    otherUser: true,
  }
  room.chats[room.chatPage].message.push(message)
}

export async function processMinecraftDialogue(payload: MinecraftDialoguePayload): Promise<BridgeResponse> {
  const ais = aiParticipants(payload)
  if (ais.length === 0) throw new Error('Minecraft dialogue request contains no AI character')

  const {room, index} = ensureGroup(payload, ais)
  const preferred = new Set(payload.preferredResponderIds)
  const selected = ais.filter(participant => preferred.has(participant.participantId))
    .slice(0, Math.max(1, payload.maxResponses))
  const responders = selected.length ? selected : ais.slice(0, 1)
  const activeCharacterIds = new Set(responders.map(participant => participant.resolvedCharacterId))

  room.characterActive = room.characters.map(id => activeCharacterIds.has(id))
  room.characterTalks = room.characters.map(id => activeCharacterIds.has(id) ? 1 : 0)
  room.orderByOrder = true
  room.oneAtTime = true

  const chat = room.chats[room.chatPage]
  appendPlayerMessage(room, payload)
  const before = chat.message.length
  const oldNote = chat.note
  chat.note = oldNote ? `${oldNote}\n${worldNote(payload)}` : worldNote(payload)

  try {
    selectedCharID.set(index)
    const generated = await sendChat()
    if (!generated) throw new Error('RisuAI sendChat returned false')

    const currentListeners = payload.participants
      .filter(participant => participant.kind === 'PLAYER')
      .map(participant => participant.participantId)
    const messages = chat.message.slice(before)
      .filter(message => message.role === 'char')
      .map(message => {
        const participant = ais.find(ai => ai.resolvedCharacterId === message.saying)
        return {
          speakerId: participant?.participantId ?? '',
          text: message.data,
          listenerIds: currentListeners,
        }
      })
      .filter(message => message.speakerId)
    return {messages}
  } finally {
    // Runtime position/weather/inventory must not remain as an author note for later sessions.
    chat.note = oldNote
  }
}
