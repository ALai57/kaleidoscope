CREATE TABLE content(
       article_id INT,
       content text,
       dynamicjs text[]
       );
INSERT INTO content VALUES
       (1, '<div><h1>This is an example content piece</h1><h2>This is second example content piece</h2><div id="test-paragraph.js" class="dynamicjs"></div><p>This is fourth example content piece</p></div>', '{test-paragraph.js}'),
       (2, '<p>Content from 2</p>', '{a.js, b.js}'),
       (3, '<p>Content from 3</p>', '{}');
