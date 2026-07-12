ALTER TABLE recipes DROP CONSTRAINT fk_recipes_scrape_processing_run;
--;;
ALTER TABLE recipes DROP COLUMN scrape_processing_run_id;
--;;
DROP TABLE processing_runs;
--;;
DROP TABLE raw_scrapes;
