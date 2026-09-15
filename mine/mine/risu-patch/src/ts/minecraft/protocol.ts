export type ParticipantKind = 'PLAYER'|'PHYSICAL_NPC'|'DIVINE'

export interface ParticipantRef {
  participantId: string
  kind: ParticipantKind
  displayName: string
  playerUuid?: string | null
  entityUuid?: string | null
  risuCharacterId?: string | null
}

export interface ConversationMessage {
  messageId: string
  createdAtEpochMs: number
  kind: 'PLAYER'|'CHARACTER'|'SYSTEM'
  speakerId: string
  text: string
  listenerIds: string[]
}

export interface MinecraftDialoguePayload {
  requestId: string
  sessionId: string
  triggeringSpeakerId: string
  playerText: string
  participants: ParticipantRef[]
  transcript: ConversationMessage[]
  preferredResponderIds: string[]
  maxResponses: number
  allowInterjection: boolean
  authoritativeWorldState: Record<string, unknown>
}

export interface QueueJob {
  id: string
  createdAt: number
  payload: MinecraftDialoguePayload
}

export interface BridgeMessage {
  speakerId: string
  text: string
  listenerIds: string[]
}

export interface BridgeResponse {
  messages: BridgeMessage[]
}
