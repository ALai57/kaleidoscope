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
    s.setAttribute('class', 'dynamicjs');
    document.getElementById('primary-content').appendChild(s); }

function loadMathJax (url) {
    var s = document.createElement('script');
    s.setAttribute('src', url);
    s.setAttribute('type', 'text/javascript');
    s.setAttribute('class', 'dynamicjs');
    document.getElementById('primary-content').appendChild(s); }

function addDiv (divname) {
    var div = document.createElement('div');
    div.setAttribute('id', divname);
    div.setAttribute('class', 'dynamicjs');
    document.getElementById('primary-content').appendChild(div); }

loadScript('https://ajax.googleapis.com/ajax/libs/jquery/3.2.1/jquery.min.js');
loadScript('https://cdnjs.cloudflare.com/ajax/libs/d3/5.9.7/d3.min.js');
loadMathJax('https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.2/MathJax.js?config=TeX-AMS-MML_SVG');

addDiv('EquationExplode')

/////////////////////////////////////////////////////
// Helper functions
/////////////////////////////////////////////////////

function translate(xIn,yIn){
    return "translate(" + xIn + "," + yIn + ")";
}
function translateRotate(xIn,yIn,rIn,wdth,hgt){
    return "translate(" + xIn + "," + yIn + ")" + "rotate(" + rIn + "," + wdth + "," + hgt + ")";;
}

function calcWidth(w){
    return Math.abs(x(w)-x(0));
}
function calcHeight(h){
    return Math.abs(y(h)-y(0));
}


//SVG drawing size
var wd = 500;
var ht = 400;

var explodeSVG;
var theEqnSVG;

var eqnWhole;
var eqnRHS;
var eqnTop_1,   eqnTop_Bar, eqnTop_Denom;
var eqnDenom_1, eqnDenom_P, eqnDenom_e;
var eqnExp1_n;
var eqnX, eqnY, eqnZ;
var eqnX_Right, eqnX_Left, eqnX_P;
var eqnX_Left_Bar, eqnX_Left_Denom
var eqnX_Right_Bar, eqnX_Right_Denom;
var eqnW5, eqnW6;
var eqnExp2_leftP, eqnExp2_rightP;
var eqnExp3_leftP, eqnExp3_rightP;

var eqnTop_Bar_Previous;
var eqnDenom_1_Previous, eqnDenom_P_Previous, eqnDenom_e_Previous;
var eqnExp1_n_Previous;
var eqnX_Previous, eqnY_Previous, eqnZ_Previous;
var eqnX_Right_Previous, eqnX_Left_Previous, eqnX_P_Previous;
var eqnW5_Previous, eqnW6_Previous;
var eqnX_Left_Bar_Previous, eqnX_Right_Bar_Previous;
var eqnX_Left_Denom_Previous, eqnX_Right_Denom_Previous;
var eqnX_Left_Bar_w_Previous, eqnX_Right_Bar_w_Previous;

var EXPLODE_STATE=1;
var EXPLODE_DIRECTION=1;


/////////////////////////////////////////////////////
// Equation setup
/////////////////////////////////////////////////////
whenAvailable('MathJax', function (t) {
whenAvailable('d3', function (t) {

    function nextExplode(){
        if (EXPLODE_STATE === 1){
	    explodeEqn();
            EXPLODE_DIRECTION=1;
	    EXPLODE_STATE=EXPLODE_STATE+EXPLODE_DIRECTION;
        }else if (EXPLODE_STATE === 2){
	    if (EXPLODE_DIRECTION === 1 ) {
                explodeEqn2();} else {undoExplodeEqn();}
	    EXPLODE_STATE=EXPLODE_STATE+EXPLODE_DIRECTION;
        }else if (EXPLODE_STATE === 3){
            if (EXPLODE_DIRECTION === 1 ) {
                explodeEqn3();} else {undoExplodeEqn2();}
	    EXPLODE_STATE=EXPLODE_STATE+EXPLODE_DIRECTION;
        }else if (EXPLODE_STATE === 4){
	    if (EXPLODE_DIRECTION === 1 ) {
                explodeEqn4();} else {undoExplodeEqn3();}
	    EXPLODE_STATE=EXPLODE_STATE+EXPLODE_DIRECTION;
        }else if (EXPLODE_STATE === 5) {
            undoExplodeEqn4();
            EXPLODE_DIRECTION=-1;
            EXPLODE_STATE=EXPLODE_STATE+EXPLODE_DIRECTION;
        }
    }

    var x = d3.scaleLinear().domain([-2, 10])
        .range([0, wd]);
    var y = d3.scaleLinear().domain([5,-5])
        .range([0, ht]);

    MathJax.Hub.Config({
        skipStartupTypeset:true
    });

    var xEq = d3.scaleLinear().domain([-2, 10])
        .range([0, 600]);
    var yEq = d3.scaleLinear().domain([5,-5])
        .range([0, 600]);

    MathJax.Hub.Config({
        jax: ["input/TeX","output/SVG", "output/PreviewHTML"],
        extensions: ["tex2jax.js","MathMenu.js","MathZoom.js", "fast-preview.js", "AssistiveMML.js", "a11y/accessibility-menu.js"],
        TeX: {
	    extensions: ["AMSmath.js","AMSsymbols.js","noErrors.js","noUndefined.js"]
        }
    });
    MathJax.Hub.Queue(function () {

        explodeSVG = d3.select('#EquationExplode')
	    .append("svg")
	    .style("width",600)
	    .style("height",600)
	    .on('click',function(){nextExplode()});

        var theEqn = explodeSVG.append("foreignObject")
            .attr("width", 600)
            .attr("height", 500)
            .attr('y', yEq(5))
            .append("xhtml:body");

        theEqn.append('div')
	    .style('font-size','60%')
	    .attr('id','explodedEqn')
	    .text(' \\[ \\Huge{ S_{pred} = \\frac{1}{1+e^{ - \\left(\\frac{w_5 }{1 + e^{-\\left(T\\cdot w_1+ {HR}\\cdot w_2 \\right)}} +\\frac{w_6}{1 + e^{-\\left(T\\cdot w_3+ {HR}\\cdot w_4\\right)}} \\right) } }  }  \\] ');

    });

    MathJax.Hub.Queue(["Typeset",MathJax.Hub]);

    MathJax.Hub.Queue(function () {
        //Parsing helper functions
        function filter_nodes(nodes, ii, id) {
            return nodes.filter(function(d,i){return i === ii;})
	        .attr('id', id);}
        function select_first_children(base_node) {
            return base_node.selectAll(function(){return this.childNodes});}
        function select_second_children(base_node) {
            return base_node.selectAll(function(){return this.childNodes})
                .selectAll(function(){return this.childNodes});}

        //Parsing whole equation
        theEqnSVG = d3.select('#explodedEqn').selectAll('svg')
	    .attr('width',580)
	    .attr('height',580);

	eqnWhole = select_first_children(theEqnSVG);
	eqnWhole.attr('transform', "matrix(1, 0, 0, -1, 0, -20000)" )
	    .attr('id','eqn_Whole');

	eqnRHS = eqnWhole.selectAll(function(){return this.childNodes})
	    .filter(function(d,i){return i === 3;});
	eqnRHS.attr('id','eqn_RHS');

	//Parsing the RHS of the equation
       	var temp;
	temp = select_second_children(eqnRHS);
        eqnTop_Bar   = filter_nodes(temp, 0, 'eqnTop_Bar');
        eqnTop_1     = filter_nodes(temp, 1, 'eqnTop_1');
        eqnTop_Denom = filter_nodes(temp, 2, 'eqnTop_Denom');

	//Parsing the denominator of the equation
	temp = select_first_children(eqnTop_Denom);
	eqnDenom_1 = filter_nodes(temp, 0, 'eqnDenom_1');
        eqnDenom_P = filter_nodes(temp, 1, 'eqnDenom_P');

	temp = temp.filter(function(d,i){return i === 2;})
	    .selectAll(function(){return this.childNodes});
        eqnExp1_e = filter_nodes(temp, 0, 'eqnExp1_e');

	temp = temp.filter(function(d,i){return i === 1;})
	    .selectAll(function(){return this.childNodes});
	eqnExp1_n = filter_nodes(temp, 0, 'eqnExp1_n');

	temp = temp.filter(function(d,i){return i === 1;})
	    .selectAll(function(){return this.childNodes});
        eqnExp1_leftP  = filter_nodes(temp, 0, 'eqnExp1_leftP');
        eqnX           = filter_nodes(temp, 1, 'eqnX');
        eqnExp1_rightP = filter_nodes(temp, 2, 'eqnExp1_rightP');

	//Parsing equation X
	temp = select_first_children(eqnX);
        eqnX_Left  = filter_nodes(temp, 0, 'eqnX_Left');
        eqnX_P     = filter_nodes(temp, 1, 'eqnX_P');
        eqnX_Right = filter_nodes(temp, 2, 'eqnX_Right');

	temp = select_first_children(eqnX_Left);
        eqnX_Left_Bar   = filter_nodes(temp, 0, 'eqnX_Left_Bar');
        eqnW5           = filter_nodes(temp, 1, 'eqnW5');
        eqnX_Left_Denom = filter_nodes(temp, 2, 'eqnX_Left_Denom');

	temp = select_second_children(eqnX_Right);
        eqnX_Right_Bar   = filter_nodes(temp, 0, 'eqnX_Right_Bar');
        eqnW6            = filter_nodes(temp, 1, 'eqnW6');
        eqnX_Right_Denom = filter_nodes(temp, 2, 'eqnX_Right_Denom');

	//Parsing left denominator of equation X
	temp = select_first_children(eqnX_Left_Denom);
	eqnX_Left_Denom_1  = filter_nodes(temp, 0, 'eqnX_Left_Denom_1');
        eqnX_Left_Denom_P  = filter_nodes(temp, 1, 'eqnX_Left_Denom_P');

	temp = temp.filter(function(d,i){return i === 2;})
	    .selectAll(function(){return this.childNodes});
	eqnExp2_e = filter_nodes(temp, 0, 'eqnExp2_e');

        temp = temp.filter(function(d,i){return i === 1;})
	    .selectAll(function(){return this.childNodes});
        eqnExp2_n = filter_nodes(temp, 0, 'eqnExp2_n');

	temp = temp.filter(function(d,i){return i === 1;})
	    .selectAll(function(){return this.childNodes});
        eqnExp2_leftP   = filter_nodes(temp, 0, 'eqnExp2_leftP');
        eqnY            = filter_nodes(temp, 1, 'eqnY');
        eqnExp2_rightP  = filter_nodes(temp, 2, 'eqnExp2_rightP');

	//Parsing right denominator of equation X
	temp = select_first_children(eqnX_Right_Denom);
        eqnX_Right_Denom_1 = filter_nodes(temp, 0, 'eqnX_Right_Denom_1');
        eqnX_Right_Denom_P = filter_nodes(temp, 1, 'eqnX_Right_Denom_P');

	temp = temp.filter(function(d,i){return i === 2;})
	    .selectAll(function(){return this.childNodes});
	eqnExp3_e = filter_nodes(temp, 0, 'eqnExp3_e');

	temp = temp.filter(function(d,i){return i === 1;})
	    .selectAll(function(){return this.childNodes});
	eqnExp3_n = filter_nodes(temp, 0, 'eqnExp3_n');

	temp = temp.filter(function(d,i){return i === 1;})
	    .selectAll(function(){return this.childNodes});
        eqnExp3_leftP   = filter_nodes(temp, 0, 'eqnExp3_leftP');
        eqnZ            = filter_nodes(temp, 1, 'eqnZ');
        eqnExp3_rightP  = filter_nodes(temp, 2, 'eqnExp3_rightP');


	d3.select('#explodedEqn').selectAll('svg')
            .attr('viewBox',"0 -4000 64078 9602.6");

	//Get initial settings
	eqnX_Previous        =  eqnX.attr('transform');
	eqnY_Previous        =  eqnY.attr('transform');
	eqnZ_Previous        =  eqnZ.attr('transform');

	eqnTop_Bar_Previous  = [eqnTop_Bar.attr('width'),
				eqnTop_Bar.attr('x')];
	eqnDenom_1_Previous  = [eqnDenom_1.attr('x'),
				eqnDenom_1.attr('y')];
	eqnDenom_P_Previous  = [eqnDenom_P.attr('x'),
				eqnDenom_P.attr('y')];
	eqnExp1_e_Previous   = [eqnExp1_e.attr('x'),
				eqnExp1_e.attr('y')];
	eqnExp1_n_Previous   = [eqnExp1_n.attr('x'),
				eqnExp1_n.attr('y')];
	eqnX_P_Previous_t    =  eqnX_P.attr('transform');
	eqnX_Right_Previous  =  eqnX_Right.attr('transform');
	eqnW5_Previous       =  eqnW5.attr('transform');
	eqnW6_Previous       =  eqnW6.attr('transform');
	eqnX_P_Previous      =	eqnX_P.attr('x');
	eqnX_Left_Bar_Previous    = [eqnX_Left_Bar.attr('x'),
				     eqnX_Left_Bar.attr('y')];
	eqnX_Left_Denom_Previous  = eqnX_Left_Denom.attr('transform');
	eqnX_Right_Bar_Previous   = [eqnX_Right_Bar.attr('x'),
				     eqnX_Right_Bar.attr('y')];
	eqnX_Right_Denom_Previous = eqnX_Right_Denom.attr('transform');

	eqnX_Left_Bar_w_Previous =  eqnX_Left_Bar.attr('width');
	eqnX_Right_Bar_w_Previous =  eqnX_Right_Bar.attr('width');

    });

    //////////////// LEFT OFF HERE
    function explodeEqn(){
	eqnX.transition().duration(1000)
	    .attr('transform', 'translate(-4500,-8000)')
	    .on('end',function(){

	        eqnTop_Bar.transition().duration(1000)
		    .attr('width',10000)
		    .attr('x',13000);
	        eqnTop_1.transition().duration(1000)
		    .attr('x',7000)
		    .attr('y',800);
	        eqnDenom_1.transition().duration(1000)
		    .attr('x',5800)
		    .attr('y',1000);
	        eqnDenom_P.transition().duration(1000)
		    .attr('x',6300)
		    .attr('y',1000);
	        eqnExp1_leftP.transition().duration(100)
		    .style('opacity',0);
	        eqnExp1_rightP.transition().duration(100)
		    .style('opacity',0);
	        eqnExp1_n.transition().duration(1000)
		    .attr('x',7000)
		    .attr('y',1100);

	        eqnExp1_e.transition().duration(1000)
		    .attr('x',5700)
		    .attr('y',1000)
		    .on('end',function(){

	                explodeSVG.selectAll('#In3_Eqn').data([0])
		            .enter().append('text')
		            .style('opacity',0)
		            .attr('id','In3_Eqn')
		            .attr('x', xEq(-1.1))
		            .attr('y', yEq(1.46))
		            .transition().delay(500)
		            .attr('x', xEq(-1.1))
		            .attr('y', yEq(1.46))
		            .style('opacity',1)
		            .text('In3 =')
		            .style('font-size','14pt')
		            .style('font-family','Times');

	                explodeSVG.selectAll('#In3_Neuron').data([0])
		            .enter().append('text')
		            .style('opacity',0)
		            .attr('id','In3_Neuron')
		            .attr('x', xEq(3.6))
		            .attr('y',  yEq(3.0))
		            .transition().delay(500)
		            .attr('x', xEq(3.6))
		            .attr('y',  yEq(2.95))
		            .style('opacity',1)
		            .text('In3')
		            .style('font-family','Times')
		            .style('font-size','14pt');

	                explodeSVG.selectAll('#neuron3').data([0])
		            .enter().append('circle')
		            .style('fill','none')
		            .style('stroke','red')
		            .attr('id','neuron3')
                            .attr('cx', xEq(3.15))
		            .attr('cy',  yEq(3.2))
		            .transition().delay(500)
		            .attr('r', 60);
		    });

	    });

    }

    function undoExplodeEqn(){

	d3.selectAll('svg').selectAll('#In3_Eqn').remove();
	d3.selectAll('svg').selectAll('#In3_Neuron').remove();
	d3.selectAll('svg').selectAll('#neuron3').remove();

	eqnX.transition().duration(1000)
	    .attr('transform', eqnX_Previous);

	eqnTop_Bar.transition().duration(1000)
	    .attr('width',eqnTop_Bar_Previous[0])
	    .attr('x',eqnTop_Bar_Previous[1]);
	eqnDenom_1.transition().duration(1000)
	    .attr('x',eqnDenom_1_Previous[0])
	    .attr('y',eqnDenom_1_Previous[1]);
	eqnDenom_P.transition().duration(1000)
	    .attr('x',eqnDenom_P_Previous[0])
	    .attr('y',eqnDenom_P_Previous[1]);
	eqnExp1_e.transition().duration(1000)
	    .attr('x',eqnExp1_e_Previous[0])
	    .attr('y',eqnExp1_e_Previous[1]);
	eqnExp1_n.transition().duration(1000)
	    .attr('x',eqnExp1_n_Previous[0])
	    .attr('y',eqnExp1_n_Previous[1])
	    .on('end',function(){
	        eqnExp1_leftP.transition().delay(250).duration(100)
		    .style('opacity',1);
	        eqnExp1_rightP.transition().delay(250).duration(100)
		    .style('opacity',1);
	    });
    }

    function explodeEqn2(){
	eqnX_P.transition().duration(1000)
	    .attr('transform','scale(2.4)');
	eqnX_Right.transition().duration(1000)
	    .attr('transform','translate(28000,0)');
	eqnW5.transition().duration(1000)
	    .attr('transform','translate(21000,0)');
	eqnW6.transition().duration(1000)
	    .attr('transform','translate(21000,0)');

	explodeSVG.selectAll('.myones').data([1.8,7])
	    .enter().append('text')
	    .attr('class','myones')
	    .text('1')
	    .style('font-family','Times')
	    .style('opacity',0)
	    .style('font-size',22)
	    .attr('x',function(d){return xEq(d);})
	    .attr('y', yEq(1.7))
	    .transition().duration(1000)
	    .style('opacity',1);

	explodeSVG.selectAll('.dots').data([295, 549])
	    .enter().append('text')
	    .attr('class','dots')
	    .text('.')
	    .style('font-family','Times')
	    .style('opacity',0)
	    .style('font-size',62)
	    .attr('x',function(d){return d;})
	    .attr('y', yEq(1.5))
	    .transition().duration(1000)
	    .style('opacity',1);
    }

    function undoExplodeEqn2(){

	d3.selectAll('svg').selectAll('.myones').remove();
	d3.selectAll('svg').selectAll('.dots').remove();

	eqnX_P.transition().duration(1000)
	    .attr('transform',eqnX_P_Previous_t);
	eqnX_Right.transition().duration(1000)
	    .attr('transform',eqnX_Right_Previous);

	eqnW5.transition().duration(1000)
	    .attr('transform',eqnW5_Previous);
	eqnW6.transition().duration(1000)
	    .attr('transform',eqnW6_Previous);

    }

    function explodeEqn3(){
	var temp = explodeSVG.selectAll('.myones');

	temp.transition().duration(1000)
	    .attr('y', yEq(-0.5));

	eqnX_Left_Bar.transition().duration(1000)
	    .attr('y',-14500);
	eqnX_Left_Denom.transition().duration(1000)
	    .attr('transform','translate(149,-18000)')
	    .on('end',function(){

	        explodeSVG.selectAll('#yvar_Eqn').data([0])
		    .enter().append('text')
		    .style('opacity',0)
		    .attr('id','yvar_Eqn')
		    .attr('x', xEq(-1.1))
		    .attr('y',  yEq(-0.8))
		    .transition().delay(500)
		    .attr('x', xEq(-1.1))
		    .attr('y',  yEq(-0.8))
		    .style('opacity',1)
		    .text('y =')
		    .style('font-size','18pt')
		    .style('font-family','Times');

	        explodeSVG.selectAll('#yvar_Neuron').data([0])
		    .enter().append('text')
		    .style('opacity',0)
		    .attr('id','yvar_Neuron')
		    .style('font-size',22)
		    .text('y')
		    .attr('x', xEq(1.4))
		    .attr('y',  yEq(1.46))
		    .transition().delay(500)
		    .attr('x', xEq(1.4))
		    .attr('y',  yEq(1.46))
		    .style('opacity',1)
		    .style('font-family','Times');

	    });

	eqnX_Right_Bar.transition().duration(1000)
	    .attr('y',-14500);
	eqnX_Right_Denom.transition().duration(1000)
	    .attr('transform','translate(149,-18000)')
	    .on('end',function(){

	        explodeSVG.selectAll('#zvar_Eqn').data([0])
		    .enter().append('text')
		    .style('opacity',0)
		    .attr('id','zvar_Eqn')
		    .attr('x', xEq(4.6))
		    .attr('y',  yEq(-0.8))
		    .transition().delay(500)
		    .attr('x', xEq(4.6))
		    .attr('y',  yEq(-0.8))
		    .style('opacity',1)
		    .text('z =')
		    .style('font-size','18pt')
		    .style('font-family','Times');

	        explodeSVG.selectAll('#zvar_Neuron').data([0])
		    .enter().append('text')
		    .style('opacity',0)
		    .attr('id','zvar_Neuron')
		    .style('font-size',22)
		    .text('z')
		    .attr('x', xEq(7))
		    .attr('y',  yEq(1.46))
		    .transition().delay(500)
		    .attr('x', xEq(7))
		    .attr('y',  yEq(1.46))
		    .style('opacity',1)
		    .style('font-family','Times')
		    .on('end',function(){return consolidateXeqn();});
	    });
    }

    function consolidateXeqn(){
	d3.selectAll('#In3_Eqn').transition().duration(1000)
	    .attr('x',xEq(0.5));
	d3.selectAll('#yvar_Neuron').transition().duration(1000)
	    .attr('x',xEq(1.75));
	d3.selectAll('.dots').transition().duration(1000)
	    .attr('x',function(d,i){return xEq(2.1)+i*125;});
	eqnW5.transition().duration(1000)
	    .attr('transform','translate(12000, 300)');
	eqnW6.transition().duration(1000)
	    .attr('transform','translate(-2300, 300)');

	eqnX_P.transition().duration(1000)
	    .attr('x',7100);
	d3.selectAll('#zvar_Neuron').transition().duration(1000)
	    .attr('x',xEq(4.2));

	explodeSVG.selectAll('#input3').data([0])
	    .enter().append('rect')
	    .style('fill','none')
	    .style('stroke','red')
	    .attr('id','input3')
	    .attr('x',170)
	    .attr('y',185)
	    .transition().delay(500)
	    .attr('rx', 20)
	    .attr('ry', 20)
	    .attr('width',230)
	    .attr('height',50);
    }

    function undoExplodeEqn3(){

	d3.selectAll('svg').selectAll('#yvar_Eqn').remove();
	d3.selectAll('svg').selectAll('#yvar_Neuron').remove();

	d3.selectAll('svg').selectAll('#zvar_Eqn').remove();
	d3.selectAll('svg').selectAll('#zvar_Neuron').remove();

	d3.selectAll('svg').selectAll('#input3').remove();

	d3.selectAll('svg').selectAll('.myones')
	    .transition().duration(1000)
	    .attr('y', yEq(1.7));

	d3.selectAll('svg').selectAll('.dots')
	    .transition().duration(1000)
	    .attr('x',function(d){return d;})
	    .attr('y', yEq(1.5));

	eqnX_P.transition().duration(1000)
	    .attr('x',eqnX_P_Previous);

	eqnW5.transition().duration(1000)
	    .attr('transform','translate(21000, 0)');

	eqnW6.transition().duration(1000)
	    .attr('transform','translate(21000, 0)');

	eqnX_Left_Bar.transition().duration(1000)
	    .attr('y',eqnX_Left_Bar_Previous[1]);
	eqnX_Left_Denom.transition().duration(1000)
	    .attr('transform',eqnX_Left_Denom_Previous);


	eqnX_Right_Bar.transition().duration(1000)
	    .attr('y',eqnX_Right_Bar_Previous[1]);
	eqnX_Right_Denom.transition().duration(1000)
	    .attr('transform',eqnX_Right_Denom_Previous);

	d3.selectAll('svg').selectAll('#In3_Eqn')
	    .transition().duration(1000)
	    .attr('x', xEq(-1.1))
	    .attr('y',  yEq(1.46))
    }

    function explodeEqn4(){
	eqnX_Left_Bar.transition().duration(1000)
	    .attr('x',-6000);
	eqnX_Right_Bar.transition().duration(1000)
	    .attr('x',-6000);

	eqnX_Left_Denom.transition().duration(1000)
	    .attr('transform','translate(-1500,-18000)');
	eqnX_Right_Denom.transition().duration(1000)
	    .attr('transform','translate(-1500,-18000)');
	explodeSVG.selectAll('.myones').transition().duration(1000)
	    .attr('x',function(d,i){return xEq(0.4)+i*(xEq(5.15)-xEq(0));});

	eqnExp2_leftP.transition().duration(100)
	    .style('opacity',0);
	eqnExp2_rightP.transition().duration(100)
	    .style('opacity',0);
	eqnExp3_leftP.transition().duration(100)
	    .style('opacity',0);
	eqnExp3_rightP.transition().duration(100)
	    .style('opacity',0);

	explodeSVG.selectAll('#yvar_Eqn')
	    .transition().delay(500)
	    .attr('x', xEq(-1.5));

	eqnY.transition().duration(1000)
	    .attr('transform', 'translate(-5000,-8500)')
	    .on('end',function(){

		d3.selectAll('svg').selectAll('#yvar_Eqn')
		    .transition().delay(500)
		    .attr('x', xEq(-1.5));
	    });

	eqnX_Left_Bar.transition().duration(1000)
	    .attr('width',8000)
            .attr('transform', 'translate(-1900,0)');
	eqnX_Right_Bar.transition().duration(1000)
	    .attr('width',8000)
            .attr('transform', 'translate(-1900,0)');

	explodeSVG.selectAll('#zvar_Eqn')
	    .transition().delay(500)
	    .attr('x', xEq(3.7));

	eqnZ.transition().duration(1000)
	    .attr('transform', 'translate(-5000,-8500)')
	    .on('end',function(){


	        explodeSVG.selectAll('#neuron2').data([0])
		    .enter().append('circle')
		    .style('fill','none')
		    .style('stroke','red')
		    .attr('id','neuron2')
		    .attr('cx', xEq(5.8))
		    .attr('cy',  yEq(-0.75))
		    .transition().delay(500)
		    .attr('r', 60);

	        explodeSVG.selectAll('#neuron1').data([0])
		    .enter().append('circle')
		    .style('fill','none')
		    .style('stroke','red')
		    .attr('id','neuron1')
		    .attr('cx', xEq(0.7))
		    .attr('cy',  yEq(-0.75))
		    .transition().delay(500)
		    .attr('r', 60);
	    });

	explodeSVG.selectAll('#In1_Neuron').data([0])
	    .enter().append('text')
	    .style('opacity',0)
	    .attr('id','In1_Neuron')
	    .attr('x', xEq(1))
	    .attr('y',  yEq(-1.15))
	    .transition().delay(500)
	    .style('opacity',1)
	    .text('In1')
	    .style('font-size','14pt')
	    .style('font-family','Times');

	explodeSVG.selectAll('#In1_Eqn').data([0])
	    .enter().append('text')
	    .style('opacity',0)
	    .attr('id','In1_Eqn')
	    .style('font-size','14pt')
	    .text('In1 = ')
	    .attr('x', xEq(-1.2))
	    .attr('y',  yEq(-2.4))
	    .transition().delay(500)
	    .style('opacity',1)
	    .style('font-family','Times');

	explodeSVG.selectAll('#In2_Neuron').data([0])
	    .enter().append('text')
	    .style('opacity',0)
	    .attr('id','In2_Neuron')
	    .attr('x', xEq(6.05))
	    .attr('y',  yEq(-1.15))
	    .transition().delay(500)
	    .style('opacity',1)
	    .text('In2')
	    .style('font-size','14pt')
	    .style('font-family','Times');

	explodeSVG.selectAll('#In2_Eqn').data([0])
	    .enter().append('text')
	    .style('opacity',0)
	    .attr('id','In2_Eqn')
	    .style('font-size','14pt')
	    .text('In2 = ')
	    .attr('x', xEq(3.8))
	    .attr('y',  yEq(-2.4))
	    .transition().delay(500)
	    .style('opacity',1)
	    .style('font-family','Times');

	explodeSVG.selectAll('#input1').data([0])
	    .enter().append('rect')
	    .style('fill','none')
	    .style('stroke','red')
	    .attr('id','input1')
	    .attr('x',90)
	    .attr('y',415)
	    .transition().delay(500)
	    .attr('rx', 20)
	    .attr('ry', 20)
	    .attr('width',140)
	    .attr('height',50);

	explodeSVG.selectAll('#input2').data([0])
	    .enter().append('rect')
	    .style('fill','none')
	    .style('stroke','red')
	    .attr('id','input2')
	    .attr('x',345)
	    .attr('y',415)
	    .transition().delay(500)
	    .attr('rx', 20)
	    .attr('ry', 20)
	    .attr('width',140)
	    .attr('height',50);
    }

    function undoExplodeEqn4(){

	d3.selectAll('svg').selectAll('#In1_Eqn').remove();
	d3.selectAll('svg').selectAll('#In1_Neuron').remove();

	d3.selectAll('svg').selectAll('#In2_Eqn').remove();
	d3.selectAll('svg').selectAll('#In2_Neuron').remove();

	d3.selectAll('#input1').remove();
	d3.selectAll('#input2').remove();

	d3.selectAll('#neuron1').remove();
	d3.selectAll('#neuron2').remove();

	eqnExp2_leftP.transition().duration(100)
	    .style('opacity',1);
	eqnExp2_rightP.transition().duration(100)
	    .style('opacity',1);
	eqnExp3_leftP.transition().duration(100)
	    .style('opacity',1);
	eqnExp3_rightP.transition().duration(100)
	    .style('opacity',1);

	eqnX_Left_Bar.transition().duration(1000)
	    .attr('width',eqnX_Left_Bar_w_Previous)
            .attr('transform', 'translate(200, 0)');
	eqnX_Right_Bar.transition().duration(1000)
	    .attr('width',eqnX_Right_Bar_w_Previous)
            .attr('transform', 'translate(200, 0)');

	eqnX_Right.transition().duration(1000)
	    .attr('transform','translate(28000,0)');
	eqnX_Right_Denom.transition().duration(1000)
	    .attr('transform','translate(149,-18000)');
	eqnX_Left_Denom.transition().duration(1000)
	    .attr('transform','translate(149,-18000)');

	eqnY.transition().duration(1000)
	    .attr('transform',eqnY_Previous);
	eqnZ.transition().duration(1000)
	    .attr('transform',eqnZ_Previous);

	d3.selectAll('svg').selectAll('#zvar_Eqn')
	    .transition().delay(500)
	    .attr('x', xEq(4.6));
	d3.selectAll('svg').selectAll('#yvar_Eqn')
	    .transition().delay(500)
	    .attr('x', xEq(-1.1));
	d3.selectAll('.myones')
	    .transition().duration(500)
	    .attr('x',function(d){return xEq(d);});
    }

});


});
