
INSERT INTO articles VALUES ('Neural network explanation', 'thoughts', NOW(), 'Andrew Lai', 'neural-network-explanation', 6);

INSERT INTO content VALUES (6, 2, 'text', '{"style": null}', '<h2>Interactive neural network demo - change the network settings!</h2>
The example below shows an Artificial Neural Network with 2 nodes in the input layer, 2 nodes in the hidden layer, and 1 node in the output layer. Circles represent neurons and lines represent connection weights (red for negative, black for positive weights). The white lines inside the neurons indicate the neuron bias.

Change the neural network weights and neuron bias using the sliders, then watch as the prediction errors change too!'), (6, 2, 'js', '{"style": null, "id": "the-script"}', 'neural-net.js');
