
INSERT INTO articles VALUES
('My first article', 'thoughts', NOW(), 'Andrew Lai', 'my-first-article', DEFAULT),
('My second article', 'research', NOW(), 'Andrew Lai', 'my-second-article', DEFAULT),
('My third article', 'archive', NOW(), 'Andrew Lai', 'my-third-article', DEFAULT),
('My fourth article', 'about', NOW(), 'Andrew Lai', 'my-fourth-article', DEFAULT),
('Neural network explanation', 'thoughts', NOW(), 'Andrew Lai', 'neural-network-explanation', DEFAULT);

--;;

INSERT INTO content VALUES
(1, '<div><h1>This is an example content piece</h1><h2>This is second example content piece</h2><div id="test-paragraph.js" class="dynamicjs"></div><p>This is fourth example content piece</p></div>', '{test-paragraph.js}'),
(2, '<p>Content from 2</p>', '{a.js, b.js}'),
(3, '<p>Content from 3</p>', '{}'),
(4, '<p>Content from 4</p>', '{}'),
(5, '<div><p>The example below shows an Artificial Neural Network with 2 nodes in the input layer, 2 nodes in the hidden layer, and 1 node in the output layer.</p><p>Circles represent neurons and lines represent connection weights (red for negative, black for positive weights). The white lines inside the neurons indicate the neuron bias.</p><p>Change the neural network weights and neuron bias using the sliders, then watch as the prediction errors change too!</p><div id="neural-net.js></div></div>', '{neural-net.js}');
--;;


