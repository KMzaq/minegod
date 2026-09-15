import type { QueueJob } from './protocol'
import { processMinecraftDialogue } from './risuSessionAdapter'

const BRIDGE = 'http://127.0.0.1:32145'
let running = false
const sleep = (milliseconds: number) => new Promise(resolve => setTimeout(resolve, milliseconds))

export async function startMinecraftDialogueWorker() {
  if (running) return
  running = true
  console.info('[Minecraft/Risu] worker started')
  while (running) {
    try {
      const response = await fetch(`${BRIDGE}/v1/jobs/next`)
      if (response.status === 204) {
        await sleep(250)
        continue
      }
      if (!response.ok) throw new Error(`job poll HTTP ${response.status}`)
      const job = await response.json() as QueueJob
      try {
        const output = await processMinecraftDialogue(job.payload)
        await fetch(`${BRIDGE}/v1/jobs/${job.id}/complete`, {
          method: 'POST',
          headers: {'content-type': 'application/json'},
          body: JSON.stringify(output),
        })
      } catch (error) {
        await fetch(`${BRIDGE}/v1/jobs/${job.id}/fail`, {
          method: 'POST',
          headers: {'content-type': 'application/json'},
          body: JSON.stringify({error: String(error)}),
        })
      }
    } catch (error) {
      console.error('[Minecraft/Risu] worker error', error)
      await sleep(1000)
    }
  }
}

export function stopMinecraftDialogueWorker() {
  running = false
}
