CREATE TYPE content_type AS ENUM ('text', 'js', 'other');
CREATE TABLE content(
       article_id INT,
       content_order INT,
       content_type content_type,
       metadata json,
       content text
       );
INSERT INTO content VALUES
       (1, 1, 'text', '{"style": null}', '<h1>This is an example content piece</h1>'),
       (1, 2, 'text', '{"style": null}', '<h2>This is second example content piece</h2>'),
       (1, 3,   'js', '{"style": null, "id": "the-script"}', '<script src="test-paragraph.js">'),
       (1, 4, 'text', '{"style": null}', '<p>This is fourth example content piece</p>'),
       (2, 1, 'text', '{"style": {"color": "red"}}', '<p>Content from 2</p>'),
       (3, 1, 'text', '{"style": null}', '<p>Content from 3</p>');
