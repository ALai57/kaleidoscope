
CREATE TABLE tags(
       id          UUID NOT NULL PRIMARY KEY,
       created_at  TIMESTAMP,
       modified_at TIMESTAMP,
       name        TEXT,
       hostname    VARCHAR,

       UNIQUE(name, hostname)
);

--;;

CREATE TABLE article_tags(
       id         UUID NOT NULL PRIMARY KEY,
       tag_id     UUID NOT NULL,
       article_id INT NOT NULL,
       created_at TIMESTAMP,

       UNIQUE(tag_id, article_id),

       CONSTRAINT fk_article_tags_articles
         FOREIGN KEY(article_id)
           REFERENCES articles(id),

       CONSTRAINT fk_tags
         FOREIGN KEY(tag_id)
           REFERENCES tags(id)
);

--;;

-- Attach a group to an article for permissioning
CREATE TABLE article_audiences(
       id         UUID NOT NULL PRIMARY KEY,
       group_id   VARCHAR NOT NULL,
       article_id INT NOT NULL,
       created_at TIMESTAMP,

       UNIQUE(group_id, article_id),

       CONSTRAINT fk_article_audiences__articles
         FOREIGN KEY(article_id)
           REFERENCES articles(id),

       CONSTRAINT fk_article_audiences__groups
         FOREIGN KEY(group_id)
           REFERENCES groups(id)
);

--;;

CREATE OR REPLACE VIEW full_article_audiences AS
SELECT aa.*,
       a.hostname
FROM article_audiences aa INNER JOIN articles a on aa.article_id = a.id
