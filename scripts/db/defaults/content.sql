CREATE TYPE content_type AS ENUM ('text', 'js', 'other');
CREATE TABLE content(
       article_id INT,
       content_order INT,
       content_type content_type,
       metadata json,
       content text
       );
INSERT INTO content VALUES
       (1, 1, 'text', '{"style": null}', 'This is an example content piece'),
       (1, 2, 'text', '{"style": null}', 'This is second example content piece'),
       (1, 3,   'js', '{"style": null, "id": "the-script"}', 'test-paragraph.js'),
       (1, 4, 'text', '{"style": null}', 'This is fourth example content piece'),
       (2, 1, 'text', '{"style": {"color": "red"}}', 'Content from 2'),
       (3, 1, 'text', '{"style": null}', 'Content from 3');
