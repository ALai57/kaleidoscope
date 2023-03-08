
ALTER TABLE articles ADD hostname VARCHAR;

--;;

UPDATE articles SET hostname = 'ip6-localhost' WHERE id IN (1,2);

--;;

UPDATE articles SET hostname = 'localhost' WHERE id IN (3,4);

