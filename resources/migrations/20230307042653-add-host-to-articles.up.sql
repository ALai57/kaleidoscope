
ALTER TABLE articles ADD hostname VARCHAR;

--;;

UPDATE articles SET hostname = 'localhost' WHERE id IN (1,2,3,4);
