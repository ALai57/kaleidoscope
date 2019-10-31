CREATE TYPE content_type AS ENUM ('text', 'js', 'other');
CREATE TABLE content(
       article_id INT,
       content text
       );
INSERT INTO content VALUES
       (1, '<div><h1>This is an example content piece</h1><h2>This is second example content piece</h2><p class="dynamicjs"></p><script src="js/test-paragraph.js"></script><p>This is fourth example content piece</p></div>'),
       (2, '<p>Content from 2</p>'),
       (3, '<p>Content from 3</p>');
