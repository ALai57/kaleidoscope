
INSERT INTO articles VALUES
('My first article', 'thoughts', NOW(), 'Andrew Lai', 'my-first-article', '<div><h1>This is an example content piece</h1><h2>This is second example content piece</h2><div class="dynamicjs" id="test-paragraph.js"></div><script class="dynamicjs" src="test-paragraph.js"></script><p>This is fourth example content piece</p></div>', DEFAULT),
('My second article', 'research', NOW(), 'Andrew Lai', 'my-second-article', '<p>Content from 2</p>', DEFAULT),
('My third article', 'archive', NOW(), 'Andrew Lai', 'my-third-article', '<p>Content from 3</p>', DEFAULT),
('My fourth article', 'about', NOW(), 'Andrew Lai', 'my-fourth-article','<p>Content from 4</p>', DEFAULT),
('Neural network explanation', 'thoughts', NOW(), 'Andrew Lai', 'neural-network-explanation', '<div><p>The example below shows an Artificial Neural Network with 2 nodes in the input layer, 2 nodes in the hidden layer, and 1 node in the output layer.</p><p>Circles represent neurons and lines represent connection weights (red for negative, black for positive weights). The white lines inside the neurons indicate the neuron bias.</p><p>Change the neural network weights and neuron bias using the sliders, then watch as the prediction errors change too!</p><div id="neural-net.js"></div><script class="dynamicjs" src="neural-net.js"></script></div>', DEFAULT);
