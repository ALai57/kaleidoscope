CREATE TABLE articles(
       title VARCHAR (100),
       article_tags VARCHAR (32),
       timestamp TIMESTAMP,
       author VARCHAR (50),
       article_id SERIAL PRIMARY KEY);
INSERT INTO articles VALUES
       ('My first article', 'thoughts', NOW(), 'Andrew Lai', DEFAULT),
       ('My second article', 'research', NOW(), 'Andrew Lai', DEFAULT),
       ('My third article', 'archive', NOW(), 'Andrew Lai', DEFAULT),
       ('My fourth article', 'about', NOW(), 'Andrew Lai', DEFAULT);
