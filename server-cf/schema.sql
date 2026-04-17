CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_key TEXT UNIQUE NOT NULL,
    public_key TEXT,
    name TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    last_seen TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL,
    message_id TEXT UNIQUE NOT NULL,
    title TEXT,
    body TEXT,
    image TEXT,
    group_name TEXT,
    icon TEXT,
    url TEXT,
    sound TEXT,
    badge INTEGER DEFAULT 0,
    encrypted_payload TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    delivered INTEGER DEFAULT 0,
    FOREIGN KEY (device_id) REFERENCES devices(id)
);

CREATE INDEX IF NOT EXISTS idx_messages_device_id ON messages(device_id);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages(created_at);
