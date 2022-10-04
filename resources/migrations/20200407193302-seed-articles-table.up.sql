INSERT INTO articles (id, author, article_url, article_tags, created_at, modified_at)
VALUES
(1, 'Andrew Lai', 'my-first-article' , 'thoughts', '2022-01-01T00:00:00Z', '2022-01-01T00:00:00Z'),
(2, 'Andrew Lai', 'my-second-article', 'thoughts', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z'),
(3, 'Andrew Lai', 'my-third-article' , 'thoughts', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(4, 'Andrew Lai', 'neural-network-explanation', 'thoughts', '2022-04-01T00:00:00Z', '2022-04-01T00:00:00Z');

--;;

INSERT INTO article_branches (id, article_id, published_at, branch_name, created_at, modified_at)
VALUES
(1, 1, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(2, 2, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(3, 3, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(4, 4, '2022-03-01T00:00:00Z', 'main', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(5, 4,                   NULL, 'test', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z');

--;;

INSERT INTO article_versions (id, branch_id, title, content, created_at, modified_at)
VALUES
(1, 1, 'My first article', '<div><h1>This is an h1</h1><h2>This is an h2</h2><div class="dynamicjs" id="test-paragraph.js"></div><script class="dynamicjs" src="js/test-paragraph.js"></script><p>This is a paragraph</p></div>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(2, 2, 'My Second Article', '<p>Second Article Content</p>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(3, 3, 'My Third Article', '<p>Third Article Content</p>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z'),
(4, 4, 'Neural Network Explanation', '<div><p>The example below shows an Artificial Neural Network with 2 nodes in the input layer, 2 nodes in the hidden layer, and 1 node in the output layer.</p><p>Circles represent neurons and lines represent connection weights (red for negative, black for positive weights). The white lines inside the neurons indicate the neuron bias.</p><p>Change the neural network weights and neuron bias using the sliders, then watch as the prediction errors change too!</p><div class="dynamicjs" id="neural-net.js"></div><script class="dynamicjs" src="js/neural-net.js"></script></div>', '2022-03-01T00:00:00Z', '2022-03-01T00:00:00Z');
