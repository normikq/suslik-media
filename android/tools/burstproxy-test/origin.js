const http=require('http'),fs=require('fs');const f=process.argv[2];const st=fs.statSync(f);let n=0;const START=Date.now();
http.createServer((req,res)=>{n++;const r=req.headers.range;let a=0,z=st.size-1;
 if(r){const m=/bytes=(\d*)-(\d*)/.exec(r);a=+m[1]||0;if(m[2])z=Math.min(+m[2],st.size-1);}
 const t0=Date.now();let sent=0;res.on('close',()=>console.log(`req#${n} ${r||'-'} t=${((t0-START)/1000).toFixed(1)}s dur=${((Date.now()-t0)/1000).toFixed(1)}s sent=${(sent/1048576).toFixed(1)}MB`));
 if(a>=st.size){res.writeHead(416,{'Content-Range':`bytes */${st.size}`});return res.end();}
 res.writeHead(r?206:200,{'Content-Type':'video/x-matroska','Accept-Ranges':'bytes','Content-Length':z-a+1,...(r?{'Content-Range':`bytes ${a}-${z}/${st.size}`}:{})});
 const s=fs.createReadStream(f,{start:a,end:z});s.on('data',c=>sent+=c.length);s.pipe(res);res.on('close',()=>s.destroy());
}).listen(3995,'127.0.0.1');
