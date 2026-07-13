ALTER TABLE raw_scrapes ALTER COLUMN request_url SET NOT NULL;
--;;
ALTER TABLE raw_scrapes DROP COLUMN source_kind;
--;;
ALTER TABLE raw_scrapes RENAME COLUMN raw_content TO raw_html;
