/////////////////////////////////////////////////////
// Load necessary files and setup DOM
/////////////////////////////////////////////////////
function whenAvailable(name, callback) {
    var interval = 10; // ms
    window.setTimeout(function() {
        if (window[name]) {
            callback(window[name]);
        } else {
            window.setTimeout(arguments.callee, interval);
        }
    }, interval); }

function loadScript (url) {
    var s = document.createElement('script');
    s.setAttribute('src', url);
    s.setAttribute('class', "dynamicjs");
    document.getElementById('neural-net.js').appendChild(s); }

function loadCSS (url) {
    var s = document.createElement('link');
    s.setAttribute('rel', 'stylesheet');
    s.setAttribute('href', url);
    s.setAttribute('class', "dynamicjs");
    document.getElementById('neural-net.js').appendChild(s); }

function addDiv (divname) {
    var div = document.createElement('div');
    div.setAttribute('id', divname);
    div.setAttribute('class', "dynamicjs");
    document.getElementById('neural-net.js').appendChild(div); }

loadScript('https://ajax.googleapis.com/ajax/libs/jquery/3.2.1/jquery.min.js');
loadScript('https://cdnjs.cloudflare.com/ajax/libs/d3/5.9.7/d3.min.js');
loadCSS('css/d3.slider.css');

addDiv('NetworkGraph');
addDiv('ErrorLines');

whenAvailable('d3', function (t) {
    loadScript('./js/d3.slider.js');
    loadScript('./js/d3.rebind.js');})

/////////////////////////////////////////////////////
// Data and neural network setup
/////////////////////////////////////////////////////
var myData = [
    {"x1": 2.781083600, "x2":  2.550537003, "y":  0},
    {"x1": 1.465489372, "x2":  2.362125076, "y":  0},
    {"x1": 3.396561688, "x2":  4.400293529, "y":  0},
    {"x1": 1.388070190, "x2":  1.850220317, "y":  0},
    {"x1": 3.064072320, "x2":  3.005305973, "y":  0},
    {"x1": 7.627531214, "x2":  2.759262235, "y":  1},
    {"x1": 5.332441248, "x2":  2.088626775, "y":  1},
    {"x1": 6.922596716, "x2":  1.771063670, "y":  1},
    {"x1": 8.675418651, "x2": -0.242068655, "y":  1},
    {"x1": 7.673756466, "x2":  3.508563011, "y":  1},
];

var myNeurons = [
    { "x":  0, "y": +1, "r": 50, "b":    0, "c" : "black"},
    { "x":  0, "y": -1, "r": 50, "b":    0, "c" : "black"},
    { "x":  1, "y": +1, "r": 50, "b": 1.56, "c" : "black"},
    { "x":  1, "y": -1, "r": 50, "b": 0.76, "c" : "black"},
    { "x":  2, "y":  0, "r": 50, "b": 3.66, "c" : "black"},
];

var myWeights = [
    { "fromNeuron":  0, "toNeuron": 2, "t": -2.1, "c" : "black"},
    { "fromNeuron":  0, "toNeuron": 3, "t": -1.8, "c" : "black"},
    { "fromNeuron":  1, "toNeuron": 2, "t": +2.7, "c" : "black"},
    { "fromNeuron":  1, "toNeuron": 3, "t": +2.6, "c" : "black"},
    { "fromNeuron":  2, "toNeuron": 4, "t": -6.8, "c" : "black"},
    { "fromNeuron":  3, "toNeuron": 4, "t": -1.0, "c" : "black"},
];

function forwardPropagate(d,wts){
    var networkOutput = [];

    for (i=0;i<d.length;i++) {

	//Hidden Layer
	var in3 = d[i].x1*wts[0].t + d[i].x2*wts[2].t + myNeurons[2].b;
	var in4 = d[i].x1*wts[1].t + d[i].x2*wts[3].t + myNeurons[3].b;
	var out3 = activate(in3);
	var out4 = activate(in4);

	//Output Layer
	var in5 = out3*wts[4].t+out4*wts[5].t + myNeurons[4].b;
	var out5 = activate(in5);

	    <!-- console.log(in5); -->
	    <!-- console.log(out5); -->

	obj = {"predicted" : out5, "target": d[i].y};
	networkOutput.push(obj);

    }

    return networkOutput;
}

function activate(x){
    return 1/(1+Math.exp(-x));
}




whenAvailable('d3_rebind', function (t) {
    /////////////////////////////////////////////////////
    // Create SVG and axes
    /////////////////////////////////////////////////////
    var wd = 500;
    var ht = 300;

    var svg = d3.select('#NetworkGraph').append("svg")
        .attr("class", "dynamicjs")
        .style("width",  wd)
        .style("height", ht);

    var x = d3.scaleLinear().domain([-0.5, 2.5]).range([0, wd]);
    var y = d3.scaleLinear().domain([2,-2]).range([0, ht]);
    var z = d3.scaleLinear().domain([-7,7]).range([0, 100]);
    var z_inv = d3.scaleLinear().domain([0, 100]).range([-5,5]);
    var z_rng = z.domain();

    z_rng = z_rng[1] - z_rng[0];

    /////////////////////////////////////////////////////
    // Render weights
    /////////////////////////////////////////////////////
    var weightLine = d3.select("#NetworkGraph").select("svg")
        .selectAll("line").data(myWeights);

    weightLine.enter().append("line")
        .attr("class", "dynamicjs")
        .attr(          "id", function(d,i) {return   'weight' + i  			 ;})
        .attr(          "x1", function(d)   {return   x(myNeurons[d.fromNeuron].x) ;})
        .attr(          "y1", function(d)   {return   y(myNeurons[d.fromNeuron].y) ;})
        .attr(          "x2", function(d)   {return   x(myNeurons[d.toNeuron].x) 	 ;})
        .attr(          "y2", function(d)   {return   y(myNeurons[d.toNeuron].y) 	 ;})
        .attr(      "stroke", function(d)   {return   d.c           		 	 ;})
        .attr("stroke-width", function(d)   {return   d.t           			 ;});


    /////////////////////////////////////////////////////
    // Render Neurons
    /////////////////////////////////////////////////////
    var circ = d3.select("#NetworkGraph").select("svg")
        .selectAll("circle").data(myNeurons);

    circ.enter().append("circle")
        .attr("class", "dynamicjs")
        .attr(    "r", function(d) {return   d.r  ;})
        .attr(   "cx", function(d) {return x(d.x) ;})
        .attr(   "cy", function(d) {return y(d.y) ;})
        .style("fill", function(d) {return   d.c  ;});

    /////////////////////////////////////////////////////
    // Render Biases
    /////////////////////////////////////////////////////
    var biasLine = d3.select("#NetworkGraph").select("svg")
        .selectAll(".biasline").data(myNeurons.slice(2,5) );

    biasLine.enter().append("line")
        .attr(       "class", "biasline dynamicjs")
        .attr(          "id", function(d,i) {return   'biasline' + i  ;})
        .attr(          "x1", function(d)   {return   x(d.x)            ;})
        .attr(          "y1", function(d)   {return   y(d.y) + d.r      ;})
        .attr(          "x2", function(d)   {return   x(d.x) 	          ;})
        .attr(          "y2", function(d)   {return   y(d.y) - d.r      ;})
        .attr(      "stroke", function(d)   {return   "white"         ;})
        .attr("stroke-width", function(d)   {return   3	              ;});

    /////////////////////////////////////////////////////
    // Render Separate SVG for error
    /////////////////////////////////////////////////////
    var wdE = 500;
    var htE = 100;
    var xE = d3.scaleLinear().domain([-5, 5]).range([0, wdE]);
    var yE = d3.scaleLinear().domain([11,-1]).range([0, htE]);

    var svgErr = d3.select('#ErrorLines').append("svg")
        .style("width",wdE)
        .style("height",htE + 100);

    /////////////////////////////////////////////////////
    // Render Error lines
    /////////////////////////////////////////////////////
    var eInitial = forwardPropagate(myData,myWeights);
    var errorLine = d3.select("#ErrorLines").select("svg")
        .selectAll("line").data(eInitial);

    errorLine.enter().append("line")
        .attr("class", "dynamicjs")
        .attr("id", function(d,i) {return  'error' + i              ;})
        .attr("x1", function(d,i) {return  xE(0)                    ;})
        .attr("y1", function(d,i) {return  yE(i) + 30               ;})
        .attr("x2", function(d,i) {return  xE(d.predicted-d.target) ;})
        .attr("y2", function(d,i) {return  yE(i) + 30               ;})
        .attr("stroke", function(d,i) {
	    if ((d.predicted-d.target)>=0) {return "black";}
	    else {return "red";} })
        .attr("stroke-width", function(d)   {return 3;});


    function updateError(){
        var eUpdate = forwardPropagate(myData,myWeights);

        var errorLine = d3.select("#ErrorLines").select("svg")
	    .selectAll("line").data(eUpdate);

        errorLine.transition(0,500)
	    .attr("x2", function(d,i) {return  xE(d.predicted - d.target) ;})
	    .attr("stroke", 	function(d,i) {
	        if ((d.predicted-d.target)>=0){return "black";}
	        else {return "red";} });
    }

    /////////////////////////////////////////////////////
    // Add sliders to change neuron weights
    /////////////////////////////////////////////////////

    for (i=1;i<=6;i++) {
        var div = document.createElement('div');
        div.setAttribute('id','weightSlider' + i);
        if (i==1) {var marginleft = "20px"} else {var marginleft = "10px"}
        div.setAttribute('style', 'float:left;margin-left:' + marginleft + ';margin-right:10px;');
        div.setAttribute('class', 'dynamicjs');
        document.getElementById('primary-content').appendChild(div);

    }

    function setupWeightSlider(i) {
        var newSlider = d3.slider().value(z(myWeights[i].t)).orientation("vertical")
            .on("slide",
	        function (val){
		    myWeights[i].t = z_inv(val);
		    if (z_inv(val) > 0){var c = "black";}
		    else {var c = "red";}

		    d3.select('#weight'+i)
		        .attr("stroke-width", Math.abs(z_inv(val)*5) )
		        .attr("stroke", c);

		    updateError();
	        }
	       );

        if (myWeights[i].t > 0){var c = "black";}
        else {var c = "red";}

        d3.select('#weight'+i)
	    .attr("stroke-width", Math.abs(myWeights[i].t*3) )
	    .attr("stroke", c);
        return newSlider;
    }

    d3.select('#weightSlider1').call( setupWeightSlider(0) );
    d3.select('#weightSlider2').call( setupWeightSlider(1) );
    d3.select('#weightSlider3').call( setupWeightSlider(2) );
    d3.select('#weightSlider4').call( setupWeightSlider(3) );
    d3.select('#weightSlider5').call( setupWeightSlider(4) );
    d3.select('#weightSlider6').call( setupWeightSlider(5) );

    /////////////////////////////////////////////////////
    // Add sliders to change neuron biases
    /////////////////////////////////////////////////////

    for (i=1;i<=3;i++) {
        var div = document.createElement('div');
        div.setAttribute('id','biasSlider' + i);
        if (i==1) {var marginleft = "100px"} else {var marginleft = "10px"}
        div.setAttribute('style', 'float:left;margin-left:' + marginleft + ';margin-right:10px;');
        div.setAttribute('class', 'dynamicjs');
        document.getElementById('primary-content').appendChild(div);

    }


    function setupBiasSlider(i) {
        var newSlider = d3.slider().value(z(myNeurons[i+2].b)).orientation("vertical")
            .on("slide",
	        function (val){
		    myNeurons[i+2].b = z_inv(val);

		    var xval = x(myNeurons[i+2].x) + myNeurons[i+2].b*myNeurons[i+2].r/z_rng*3/2;

		    d3.select('#biasline'+i)
		        .attr("x1", xval )
		        .attr("x2", xval );

		    updateError();
	        }
	       );

        var xval = x(myNeurons[i+2].x) + myNeurons[i+2].b*myNeurons[i+2].r/z_rng*3/2;

        d3.select('#biasline'+i)
	    .attr("x1", xval )
	    .attr("x2", xval );

        return newSlider;
    }

    d3.select('#biasSlider1').call( setupBiasSlider(0) );
    d3.select('#biasSlider2').call( setupBiasSlider(1) );
    d3.select('#biasSlider3').call( setupBiasSlider(2) );

    /////////////////////////////////////////////////////
    // Add annotations
    /////////////////////////////////////////////////////

    //Add the SVG Text Element to the svgContainer
    var textError = svgErr.selectAll("#text")
        .data([0])
        .enter()
        .append("text");

    //Add SVG Text Element Attributes
    textError
        .attr("class", "dynamicjs")
        .attr("x", 100)
        .attr("y", 20)
        .text("Neural Net Predictions (n=10)")
        .attr("font-family", "sans-serif")
        .attr("font-size", "20px")
        .attr("fill", "black")
        .attr("font-weight","bold");


    //Add the SVG Text Element to the svgContainer
    var textToSmall = svgErr.selectAll("#text")
        .data([0])
        .enter()
        .append("text");

    //Add SVG Text Element Attributes
    textToSmall
        .attr("class", "dynamicjs")
        .attr("x", 20)
        .attr("y", 80)
        .text("Too small")
        .attr("font-family", "sans-serif")
        .attr("font-size", "16px")
        .attr("fill", "black");

    //Add the SVG Text Element to the svgContainer
    var textTooLarge = svgErr.selectAll("#text")
        .data([0])
        .enter()
        .append("text");

    //Add SVG Text Element Attributes
    textTooLarge
        .attr("class", "dynamicjs")
        .attr("x", 370)
        .attr("y", 80)
        .text("Too large")
        .attr("font-family", "sans-serif")
        .attr("font-size", "16px")
        .attr("fill", "black");

    //Add the SVG Text Element to the svgContainer
    var textCnxnStr = svgErr.selectAll("#text")
        .data([0])
        .enter()
        .append("text");

    //Add SVG Text Element Attributes
    textCnxnStr
        .attr("class", "dynamicjs")
        .attr("x", 10)
        .attr("y", 180)
        .text("Connection Strengths")
        .attr("font-family", "sans-serif")
        .attr("font-size", "20px")
        .attr("fill", "black")
        .attr("font-weight","bold");

    //Add the SVG Text Element to the svgContainer
    var textBias = svgErr.selectAll("#text")
        .data([0])
        .enter()
        .append("text");

    //Add SVG Text Element Attributes
    textBias
        .attr("class", "dynamicjs")
        .attr("x", 290)
        .attr("y", 180)
        .text("Neuron Bias")
        .attr("font-family", "sans-serif")
        .attr("font-size", "20px")
        .attr("fill", "black")
        .attr("font-weight","bold");

})
