-- Photo import: the corpus is no longer HTML-only. Rename raw_html -> raw_content,
-- add a source_kind discriminator, and allow a null request_url (photos have none).
-- See plans/2026-07-12-recipe-photo-import/DESIGN.md.
ALTER TABLE raw_scrapes RENAME COLUMN raw_html TO raw_content;
--;;
ALTER TABLE raw_scrapes ADD COLUMN source_kind VARCHAR;
--;;
UPDATE raw_scrapes SET source_kind = 'url';
--;;
ALTER TABLE raw_scrapes ALTER COLUMN source_kind SET NOT NULL;
--;;
ALTER TABLE raw_scrapes ALTER COLUMN request_url DROP NOT NULL;
