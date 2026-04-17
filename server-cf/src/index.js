/**
 * Accnotify - Cloudflare Workers + D1 server
 * Replicates the Go server's push notification functionality.
 */

import { encryptMessage } from './crypto.js';
import {
  formatGitHubWebhook,
  formatGitLabWebhook,
  formatDockerWebhook,
  formatGiteaWebhook,
  formatGenericWebhook,
} from './webhook.js';

export { DeviceHub } from './hub.js';

// --- Helpers ---

function jsonResponse(data, status = 200) {
  return Response.json(data, {
    status,
    headers: corsHeaders(),
  });
}

function errorResponse(message, status = 400) {
  return jsonResponse({ code: status, message }, status);
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Device-Key',
  };
}

function generateId() {
  return crypto.randomUUID();
}

function generateDeviceKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

// --- Route matching ---

function matchRoute(method, pathname) {
  const routes = [
    { method: 'GET', pattern: /^\/$/, handler: 'root' },
    { method: 'GET', pattern: /^\/health$/, handler: 'health' },
    { method: 'POST', pattern: /^\/register$/, handler: 'register' },
    { method: 'GET', pattern: /^\/ws$/, handler: 'websocket' },
    {
      method: 'GET',
      pattern: /^\/push\/([^/]+)\/([^/]+)\/([^/]+)$/,
      handler: 'pushSimple',
    },
    { method: 'POST', pattern: /^\/push\/([^/]+)$/, handler: 'push' },
    {
      method: 'POST',
      pattern: /^\/webhook\/([^/]+)\/(github|gitlab|docker|gitea)$/,
      handler: 'webhookTyped',
    },
    { method: 'POST', pattern: /^\/webhook\/([^/]+)$/, handler: 'webhook' },
    { method: 'GET', pattern: /^\/messages\/([^/]+)$/, handler: 'getMessages' },
    {
      method: 'DELETE',
      pattern: /^\/messages\/([^/]+)\/([^/]+)$/,
      handler: 'deleteMessage',
    },
  ];

  for (const route of routes) {
    if (route.method !== method) continue;
    const match = pathname.match(route.pattern);
    if (match) {
      return { handler: route.handler, params: match.slice(1) };
    }
  }
  return null;
}

// --- DB helpers ---

async function getDeviceByKey(db, deviceKey) {
  return db
    .prepare('SELECT * FROM devices WHERE device_key = ?')
    .bind(deviceKey)
    .first();
}

async function updateLastSeen(db, deviceId) {
  await db
    .prepare("UPDATE devices SET last_seen = datetime('now') WHERE id = ?")
    .bind(deviceId)
    .run();
}

async function insertMessage(db, msg) {
  const result = await db
    .prepare(
      `INSERT INTO messages (device_id, message_id, title, body, image, group_name, icon, url, sound, badge, encrypted_payload)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .bind(
      msg.device_id,
      msg.message_id,
      msg.title || null,
      msg.body || null,
      msg.image || null,
      msg.group_name || null,
      msg.icon || null,
      msg.url || null,
      msg.sound || null,
      msg.badge || 0,
      msg.encrypted_payload || null
    )
    .run();
  return result;
}

// --- Send message through WebSocket via Durable Object ---

async function relayToWebSocket(env, deviceKey, message) {
  try {
    const id = env.DEVICE_HUB.idFromName(deviceKey);
    const hub = env.DEVICE_HUB.get(id);
    const resp = await hub.fetch(new Request('https://hub/send', {
      method: 'POST',
      body: JSON.stringify(message),
    }));
    return resp.json();
  } catch {
    return { sent: 0, connected: 0 };
  }
}

// --- Core push logic ---

async function processPush(env, deviceKey, msgData) {
  const device = await getDeviceByKey(env.DB, deviceKey);
  if (!device) {
    return errorResponse('Device not found', 404);
  }

  await updateLastSeen(env.DB, device.id);

  const messageId = msgData.message_id || generateId();

  // Handle E2E encryption if enabled and device has a public key
  let encryptedPayload = null;
  if (env.ENABLE_E2E === 'true' && device.public_key && !msgData.encrypted_payload) {
    try {
      const plaintext = JSON.stringify({
        title: msgData.title,
        body: msgData.body,
      });
      encryptedPayload = JSON.stringify(
        await encryptMessage(device.public_key, plaintext)
      );
    } catch (e) {
      console.error('Encryption failed:', e);
      // Fall through without encryption
    }
  }

  const message = {
    device_id: device.id,
    message_id: messageId,
    title: msgData.title || '',
    body: msgData.body || '',
    image: msgData.image || null,
    group_name: msgData.group || null,
    icon: msgData.icon || null,
    url: msgData.url || null,
    sound: msgData.sound || null,
    badge: parseInt(msgData.badge, 10) || 0,
    encrypted_payload: msgData.encrypted_payload || encryptedPayload,
  };

  await insertMessage(env.DB, message);

  // Relay to WebSocket
  const wsResult = await relayToWebSocket(env, deviceKey, {
    message_id: messageId,
    title: message.title,
    body: message.body,
    image: message.image,
    group: message.group_name,
    icon: message.icon,
    url: message.url,
    sound: message.sound,
    badge: message.badge,
    encrypted_payload: message.encrypted_payload,
    created_at: new Date().toISOString(),
  });

  return jsonResponse({
    code: 200,
    message: 'ok',
    data: {
      message_id: messageId,
      ws_sent: wsResult.sent || 0,
    },
  });
}

// --- Route handlers ---

async function handleHealth() {
  return jsonResponse({
    code: 200,
    message: 'ok',
    data: { version: '1.0.0-cf', runtime: 'cloudflare-workers' },
  });
}

async function handleRegister(request, env) {
  let body = {};
  try {
    body = await request.json();
  } catch {
    // empty body is fine
  }

  const deviceKey = body.device_key || generateDeviceKey();
  const name = body.name || null;
  const publicKey = body.public_key || null;

  // Check if device already exists
  const existing = await getDeviceByKey(env.DB, deviceKey);
  if (existing) {
    // Update public key and name if provided
    if (publicKey) {
      await env.DB.prepare(
        'UPDATE devices SET public_key = ?, name = COALESCE(?, name), last_seen = datetime(\'now\') WHERE device_key = ?'
      )
        .bind(publicKey, name, deviceKey)
        .run();
    }
    return jsonResponse({
      code: 200,
      message: 'ok',
      data: { device_key: deviceKey, message: 'Device updated' },
    });
  }

  await env.DB.prepare(
    'INSERT INTO devices (device_key, public_key, name) VALUES (?, ?, ?)'
  )
    .bind(deviceKey, publicKey, name)
    .run();

  return jsonResponse({
    code: 200,
    message: 'ok',
    data: { device_key: deviceKey },
  });
}

async function handlePush(request, env, params) {
  const deviceKey = decodeURIComponent(params[0]);
  let msgData = {};

  const contentType = request.headers.get('Content-Type') || '';
  if (contentType.includes('application/json')) {
    msgData = await request.json();
  } else if (contentType.includes('form')) {
    const form = await request.formData();
    for (const [key, value] of form.entries()) {
      msgData[key] = value;
    }
  }

  return processPush(env, deviceKey, msgData);
}

async function handlePushSimple(env, params) {
  const deviceKey = decodeURIComponent(params[0]);
  const title = decodeURIComponent(params[1]);
  const body = decodeURIComponent(params[2]);

  return processPush(env, deviceKey, { title, body });
}

async function handleWebSocket(request, env) {
  const url = new URL(request.url);
  // Support both 'key' (Android client) and 'device_key' param names
  const deviceKey = url.searchParams.get('key') || url.searchParams.get('device_key');
  if (!deviceKey) {
    return errorResponse('Missing device key parameter', 400);
  }

  const device = await getDeviceByKey(env.DB, deviceKey);
  if (!device) {
    return errorResponse('Device not found', 404);
  }

  await updateLastSeen(env.DB, device.id);

  // Route to the device's Durable Object
  const id = env.DEVICE_HUB.idFromName(deviceKey);
  const hub = env.DEVICE_HUB.get(id);
  return hub.fetch(new Request('https://hub/ws', {
    headers: request.headers,
  }));
}

async function handleWebhookTyped(request, env, params) {
  const deviceKey = decodeURIComponent(params[0]);
  const webhookType = params[1];

  let payload;
  try {
    payload = await request.json();
  } catch {
    return errorResponse('Invalid JSON body', 400);
  }

  let msgData;
  switch (webhookType) {
    case 'github': {
      let event = request.headers.get('X-GitHub-Event');
      if (!event) {
        // Infer event type from payload structure
        if (payload.commits) event = 'push';
        else if (payload.pull_request) event = 'pull_request';
        else if (payload.issue) event = 'issues';
        else event = 'unknown';
      }
      msgData = formatGitHubWebhook(payload, event);
      msgData.group = 'GitHub';
      msgData.icon = 'github';
      break;
    }
    case 'gitlab': {
      const event =
        request.headers.get('X-Gitlab-Event') || 'unknown';
      msgData = formatGitLabWebhook(payload, event);
      msgData.group = 'GitLab';
      msgData.icon = 'gitlab';
      break;
    }
    case 'docker': {
      msgData = formatDockerWebhook(payload);
      msgData.group = 'Docker';
      msgData.icon = 'docker';
      break;
    }
    case 'gitea': {
      const event =
        request.headers.get('X-Gitea-Event') || 'unknown';
      msgData = formatGiteaWebhook(payload, event);
      msgData.group = 'Gitea';
      msgData.icon = 'gitea';
      break;
    }
    default:
      return errorResponse('Unknown webhook type', 400);
  }

  return processPush(env, deviceKey, msgData);
}

async function handleWebhook(request, env, params) {
  const deviceKey = decodeURIComponent(params[0]);

  let payload;
  try {
    payload = await request.json();
  } catch {
    return errorResponse('Invalid JSON body', 400);
  }

  const msgData = formatGenericWebhook(payload);
  msgData.group = 'Webhook';
  return processPush(env, deviceKey, msgData);
}

async function handleGetMessages(env, params) {
  const deviceKey = decodeURIComponent(params[0]);

  const device = await getDeviceByKey(env.DB, deviceKey);
  if (!device) {
    return errorResponse('Device not found', 404);
  }

  const { results } = await env.DB.prepare(
    `SELECT message_id, title, body, image, group_name, icon, url, sound, badge, encrypted_payload, created_at, delivered
     FROM messages WHERE device_id = ? ORDER BY created_at DESC LIMIT 100`
  )
    .bind(device.id)
    .all();

  return jsonResponse({
    code: 200,
    message: 'ok',
    data: results.map((r) => ({
      message_id: r.message_id,
      title: r.title,
      body: r.body,
      image: r.image,
      group: r.group_name,
      icon: r.icon,
      url: r.url,
      sound: r.sound,
      badge: r.badge,
      encrypted_payload: r.encrypted_payload,
      created_at: r.created_at,
      delivered: !!r.delivered,
    })),
  });
}

async function handleDeleteMessage(env, params) {
  const deviceKey = decodeURIComponent(params[0]);
  const messageId = decodeURIComponent(params[1]);

  const device = await getDeviceByKey(env.DB, deviceKey);
  if (!device) {
    return errorResponse('Device not found', 404);
  }

  const result = await env.DB.prepare(
    'DELETE FROM messages WHERE device_id = ? AND message_id = ?'
  )
    .bind(device.id, messageId)
    .run();

  if (result.meta.changes === 0) {
    return errorResponse('Message not found', 404);
  }

  return jsonResponse({ code: 200, message: 'ok' });
}

// --- Main fetch handler ---

export default {
  async fetch(request, env, ctx) {
    // Handle CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    const url = new URL(request.url);
    const route = matchRoute(request.method, url.pathname);

    if (!route) {
      return errorResponse('Not found', 404);
    }

    try {
      switch (route.handler) {
        case 'root':
          return handleHealth();
        case 'health':
          return handleHealth();
        case 'register':
          return handleRegister(request, env);
        case 'push':
          return handlePush(request, env, route.params);
        case 'pushSimple':
          return handlePushSimple(env, route.params);
        case 'websocket':
          return handleWebSocket(request, env);
        case 'webhookTyped':
          return handleWebhookTyped(request, env, route.params);
        case 'webhook':
          return handleWebhook(request, env, route.params);
        case 'getMessages':
          return handleGetMessages(env, route.params);
        case 'deleteMessage':
          return handleDeleteMessage(env, route.params);
        default:
          return errorResponse('Not found', 404);
      }
    } catch (err) {
      console.error('Unhandled error:', err);
      return errorResponse('Internal server error', 500);
    }
  },
};
