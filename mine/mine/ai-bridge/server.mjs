import crypto from 'node:crypto';
import http from 'node:http';

const HOST = '127.0.0.1';
const PORT = Number(process.env.RISU_MINECRAFT_BRIDGE_PORT ?? 32145);
const TIMEOUT_MS = Number(process.env.RISU_MINECRAFT_JOB_TIMEOUT_MS ?? 120000);
const MAX_BODY_BYTES = 128 * 1024;
// The RisuAI development window is served from localhost:5174, while this
// loopback-only bridge deliberately uses a different port. Permit that local
// browser fetch without exposing the bridge beyond the machine.
const CORS_HEADERS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'content-type',
};

const queue = [];
const waitingMinecraft = new Map();
let activeJobId = null;

function json(response, status, body) {
  response.writeHead(status, {'content-type': 'application/json; charset=utf-8', ...CORS_HEADERS});
  response.end(body === undefined ? '' : JSON.stringify(body));
}

async function readJson(request) {
  let raw = '';
  for await (const chunk of request) {
    raw += chunk;
    if (Buffer.byteLength(raw) > MAX_BODY_BYTES) {
      const error = new Error(`request body exceeds ${MAX_BODY_BYTES} bytes`);
      error.statusCode = 413;
      throw error;
    }
  }
  return raw ? JSON.parse(raw) : {};
}

function removeQueuedJob(id) {
  const index = queue.findIndex(job => job.id === id);
  if (index >= 0) queue.splice(index, 1);
}

function settle(id, result) {
  const pending = waitingMinecraft.get(id);
  if (!pending) return false;
  waitingMinecraft.delete(id);
  clearTimeout(pending.timer);
  if (activeJobId === id) activeJobId = null;
  pending.resolve(result);
  return true;
}

const server = http.createServer(async (request, response) => {
  try {
    if (request.method === 'OPTIONS') {
      response.writeHead(204, CORS_HEADERS);
      return response.end();
    }

    if (request.method === 'GET' && request.url === '/health') {
      return json(response, 200, {ok: true, queued: queue.length, activeJobId});
    }

    if (request.method === 'POST' && request.url === '/v1/conversation/respond') {
      const payload = await readJson(request);
      if (!payload?.requestId || !payload?.sessionId || !Array.isArray(payload?.participants)) {
        return json(response, 400, {error: 'invalid conversation payload'});
      }
      const id = crypto.randomUUID();
      queue.push({id, createdAt: Date.now(), payload});
      const result = await new Promise(resolve => {
        const timer = setTimeout(() => {
          removeQueuedJob(id);
          settle(id, {status: 504, body: {error: 'Risu worker timeout'}});
        }, TIMEOUT_MS);
        waitingMinecraft.set(id, {resolve, timer});
      });
      return json(response, result.status, result.body);
    }

    if (request.method === 'GET' && request.url === '/v1/jobs/next') {
      if (activeJobId !== null || queue.length === 0) {
        response.writeHead(204, CORS_HEADERS);
        return response.end();
      }
      const job = queue.shift();
      activeJobId = job.id;
      return json(response, 200, job);
    }

    const completion = request.url?.match(/^\/v1\/jobs\/([^/]+)\/complete$/);
    if (request.method === 'POST' && completion) {
      const result = await readJson(request);
      if (!settle(completion[1], {status: 200, body: result})) {
        return json(response, 404, {error: 'unknown or expired job'});
      }
      return json(response, 200, {ok: true});
    }

    const failure = request.url?.match(/^\/v1\/jobs\/([^/]+)\/fail$/);
    if (request.method === 'POST' && failure) {
      const detail = await readJson(request);
      if (!settle(failure[1], {status: 500, body: {error: detail?.error ?? 'Risu worker failed'}})) {
        return json(response, 404, {error: 'unknown or expired job'});
      }
      return json(response, 200, {ok: true});
    }

    return json(response, 404, {error: 'not found'});
  } catch (error) {
    return json(response, error?.statusCode ?? 500, {error: String(error?.message ?? error)});
  }
});

server.listen(PORT, HOST, () => {
  console.log(`MythicTRPG/Risu bridge listening on http://${HOST}:${PORT}`);
});
