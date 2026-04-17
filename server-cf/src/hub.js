/**
 * DeviceHub Durable Object - manages WebSocket connections per device key.
 *
 * Each device key maps to a single Durable Object instance.
 * The DO holds active WebSocket connections and relays push messages.
 */
export class DeviceHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.sessions = [];
  }

  async fetch(request) {
    const url = new URL(request.url);

    switch (url.pathname) {
      case '/ws':
        return this.handleWebSocket(request);
      case '/send':
        return this.handleSend(request);
      case '/status':
        return this.handleStatus();
      default:
        return new Response('Not found', { status: 404 });
    }
  }

  async handleWebSocket(request) {
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response('Expected WebSocket', { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    server.accept();

    const session = { ws: server, connectedAt: Date.now() };
    this.sessions.push(session);

    server.addEventListener('message', (event) => {
      // Handle ping/pong keep-alive
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'ping') {
          server.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
        }
      } catch {
        // Ignore non-JSON messages
      }
    });

    server.addEventListener('close', () => {
      this.sessions = this.sessions.filter((s) => s.ws !== server);
    });

    server.addEventListener('error', () => {
      this.sessions = this.sessions.filter((s) => s.ws !== server);
    });

    // Send connected confirmation
    server.send(
      JSON.stringify({ type: 'connected', timestamp: Date.now() })
    );

    return new Response(null, { status: 101, webSocket: client });
  }

  async handleSend(request) {
    const message = await request.json();
    const messageId = message.message_id || '';
    const payload = JSON.stringify({
      type: 'message',
      id: messageId,
      timestamp: Math.floor(Date.now() / 1000),
      data: message,
    });

    let sent = 0;
    const deadSessions = [];

    for (const session of this.sessions) {
      try {
        session.ws.send(payload);
        sent++;
      } catch {
        deadSessions.push(session);
      }
    }

    // Clean up dead sessions
    if (deadSessions.length > 0) {
      this.sessions = this.sessions.filter(
        (s) => !deadSessions.includes(s)
      );
    }

    return Response.json({ sent, connected: this.sessions.length });
  }

  async handleStatus() {
    return Response.json({
      connected: this.sessions.length,
      sessions: this.sessions.map((s) => ({
        connectedAt: s.connectedAt,
      })),
    });
  }
}
