var div = document.getElementById("test-paragraph.js");

var para = document.createElement("P");
para.classList.add("dynamicjs");

para.innerText = "This is a paragraph -- TESTING!";
div.appendChild(para);
