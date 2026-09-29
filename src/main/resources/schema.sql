CREATE TABLE IF NOT EXISTS
sessions (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255),
    agent_mode VARCHAR(32) NOT NULL DEFAULT 'DISCUSS',
    model_provider VARCHAR(64),
    model_name VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS
messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(64) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_message_session_id ON messages(session_id);