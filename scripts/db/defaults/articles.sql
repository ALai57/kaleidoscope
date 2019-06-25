CREATE TABLE articles(
       title VARCHAR (100),
       article_tags VARCHAR (32),
       timestamp TIMESTAMP,
       author VARCHAR (50),
       article_url VARCHAR (100),
       article_id SERIAL PRIMARY KEY);
INSERT INTO articles VALUES
       ('My first article', 'thoughts', NOW(), 'Andrew Lai', 'my-first-article', DEFAULT),
       ('My second article', 'research', NOW(), 'Andrew Lai', 'my-second-article', DEFAULT),
       ('My third article', 'archive', NOW(), 'Andrew Lai', 'my-third-article', DEFAULT),
       ('My fourth article', 'about', NOW(), 'Andrew Lai', 'my-fourth-article', DEFAULT);
