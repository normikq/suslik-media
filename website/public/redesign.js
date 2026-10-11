// Янтарная «линза» под активной вкладкой навигации.
(function(){
  var nav=document.querySelector(".navrow"); if(!nav) return;
  var g=document.createElement("span"); g.className="nav-glider"; nav.prepend(g);
  var first=true;
  function place(){
    var a=nav.querySelector("button:not(.secondary)");
    if(!a||!a.offsetWidth) return;
    g.style.width=a.offsetWidth+"px"; g.style.height=a.offsetHeight+"px";
    g.style.transform="translate("+a.offsetLeft+"px,"+a.offsetTop+"px)";
    if(first){ first=false; requestAnimationFrame(function(){ g.classList.add("ready"); }); }
  }
  try{ new MutationObserver(place).observe(nav,{attributes:true,subtree:true,attributeFilter:["class"]}); }catch(e){}
  try{ new ResizeObserver(place).observe(nav); }catch(e){ addEventListener("resize",place); }
  place();
})();
