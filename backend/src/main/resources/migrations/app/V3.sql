CREATE TABLE notebooks(id TEXT PRIMARY KEY,name TEXT NOT NULL,position INTEGER NOT NULL DEFAULT 0);
CREATE TABLE note_sections(id TEXT PRIMARY KEY,notebook_id TEXT NOT NULL REFERENCES notebooks(id),name TEXT NOT NULL,position INTEGER NOT NULL DEFAULT 0);
CREATE TABLE notes(id TEXT PRIMARY KEY,section_id TEXT NOT NULL REFERENCES note_sections(id),title TEXT NOT NULL,body TEXT NOT NULL,plain_text TEXT NOT NULL DEFAULT '',revision INTEGER NOT NULL DEFAULT 1,knowledge INTEGER NOT NULL DEFAULT 0,favorite INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,indexed_revision INTEGER NOT NULL DEFAULT 0,index_error TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL);
CREATE TABLE note_history(note_id TEXT NOT NULL REFERENCES notes(id),revision INTEGER NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(note_id,revision));
CREATE TABLE note_assets(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id),name TEXT NOT NULL,file_name TEXT NOT NULL,hash TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL);
CREATE TABLE note_chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id),revision INTEGER NOT NULL,ordinal INTEGER NOT NULL,content TEXT NOT NULL,terms TEXT NOT NULL,embedding BLOB,fingerprint TEXT NOT NULL DEFAULT '');
CREATE INDEX note_chunks_note ON note_chunks(note_id);
CREATE VIRTUAL TABLE notes_fts USING fts5(chunk_id UNINDEXED,terms);
INSERT INTO notebooks VALUES('default','我的笔记',0);
INSERT INTO note_sections VALUES('default','default','随手记',0);
INSERT INTO note_sections VALUES('imports','default','导入资料',1);

INSERT INTO notes(id,section_id,title,body,plain_text,created_at,updated_at)
SELECT 'legacy-source-'||s.id,'imports',s.name,json_object('type','doc','content',json_array(json_object('type','paragraph','content',json_array(json_object('type','text','text',group_concat(g.content,char(10)||char(10))))))),group_concat(g.content,char(10)||char(10)),s.created_at,s.created_at FROM sources s JOIN segments g ON g.source_id=s.id GROUP BY s.id;
INSERT INTO notes(id,section_id,title,body,plain_text,knowledge,created_at,updated_at)
SELECT 'legacy-record-'||r.record_id,'imports',r.source_name||' · '||r.source_label,json_object('type','doc','content',json_array(json_object('type','paragraph','content',json_array(json_object('type','text','text',r.content))))),r.content,1,0,0 FROM release_records r JOIN candidates c ON c.id=r.record_id WHERE r.release_id=(SELECT value FROM settings WHERE key='knowledge.active') AND c.withdrawn=0 AND r.kind!='STYLE';
UPDATE jobs SET status='CANCELLED',error_code='NOTES_MIGRATION' WHERE type IN ('IMPORT','PARSE','EXTRACT','PUBLISH') AND status IN ('QUEUED','RUNNING','PAUSED');
