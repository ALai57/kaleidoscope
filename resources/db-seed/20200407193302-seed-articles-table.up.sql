INSERT INTO articles (id, author, hostname, article_url, article_tags, created_at, modified_at, article_title, summary)
VALUES
(1, 'Andrew Lai', 'andrewslai.localhost', 'my-first-article' , 'thoughts', '2022-01-01T00:00:00Z', '2022-01-01T00:00:00Z', 'My first article', 'An example summary'),
(2, 'Andrew Lai', 'andrewslai.localhost', 'my-second-article', 'thoughts', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z', 'My Second Article', 'An different example summary'),
(3, 'Andrew Lai', 'andrewslai.localhost', 'my-third-article' , 'thoughts', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z', 'My Third Article', 'A final example summary'),
(4, 'Andrew Lai', 'andrewslai.localhost', 'neural-network-explanation', 'thoughts', '2022-04-01T00:00:00Z', '2022-04-01T00:00:00Z', 'Neural Network Explanation', 'My summary');

--;;

INSERT INTO article_branches (id, article_id, published_at, branch_name, created_at, modified_at)
VALUES
(1, 1, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(2, 2, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(3, 3, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(4, 4, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(5, 4,                   NULL, 'test', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z');

--;;

INSERT INTO article_versions (id, branch_id, content, created_at, modified_at)
VALUES
(1, 1, '<div><h1>This is an h1</h1><h2>This is an h2</h2><div class="dynamicjs" id="test-paragraph.js"></div><script class="dynamicjs" src="js/test-paragraph.js"></script><p>This is a paragraph</p></div>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(2, 2, '<p>Second Article Content</p>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(3, 3, '<p>Third Article Content</p>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(4, 4, '<div><p>The example below shows an Artificial Neural Network with 2 nodes in the input layer, 2 nodes in the hidden layer, and 1 node in the output layer.</p><p>Circles represent neurons and lines represent connection weights (red for negative, black for positive weights). The white lines inside the neurons indicate the neuron bias.</p><p>Change the neural network weights and neuron bias using the sliders, then watch as the prediction errors change too!</p><div class="dynamicjs" id="neural-net.js"></div><script class="dynamicjs" src="js/neural-net.js"></script></div>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z');
