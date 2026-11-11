CREATE TABLE IF NOT EXISTS knowledge_doc (
    doc_id VARCHAR(64) PRIMARY KEY,
    version INT NOT NULL,
    title VARCHAR(200),
    platform VARCHAR(32),
    category VARCHAR(32),
    content_hash VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
