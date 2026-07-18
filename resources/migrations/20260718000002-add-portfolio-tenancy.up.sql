-- Portfolio had no tenancy and was served publicly, unscoped, to every site.
-- Give it a hostname. Existing rows are the original site's; backfill first.
ALTER TABLE portfolio_entries ADD COLUMN hostname VARCHAR;

--;;

UPDATE portfolio_entries SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE portfolio_entries ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE portfolio_links ADD COLUMN hostname VARCHAR;

--;;

UPDATE portfolio_links SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE portfolio_links ALTER COLUMN hostname SET NOT NULL;
