CREATE TABLE note_chunk_parents(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id),revision INTEGER NOT NULL,heading_path TEXT NOT NULL,content TEXT NOT NULL);
ALTER TABLE note_chunks ADD COLUMN parent_id TEXT REFERENCES note_chunk_parents(id);
ALTER TABLE note_chunks ADD COLUMN kind TEXT NOT NULL DEFAULT 'paragraph';
ALTER TABLE note_chunks ADD COLUMN context_text TEXT NOT NULL DEFAULT '';
ALTER TABLE notes ADD COLUMN index_fingerprint TEXT NOT NULL DEFAULT '';
CREATE INDEX note_chunks_parent ON note_chunks(parent_id);
UPDATE notes SET indexed_revision=0,index_error='' WHERE knowledge=1 AND deleted=0;
