const c=require("crypto");
const MiB=1<<20,KiB=1024;
const gen=(size)=>{const b=Buffer.alloc(size);for(let i=0;i<size;i++)b[i]=Math.imul(i,2654435761)>>>24;return b};
const fp=(buf)=>{const s=buf.length;let parts;
 if(s<3*MiB)parts=[buf];else{const m=Math.floor(s/2)-512*KiB;parts=[buf.subarray(0,MiB),buf.subarray(m,m+MiB),buf.subarray(s-MiB)];}
 return c.createHash("sha256").update(Buffer.concat([Buffer.from(s+":","utf8"),...parts])).digest("hex")};
const sign=(exp,id,sec)=>c.createHash("md5").update(exp+id+" "+sec).digest("base64url");
const hid=(s)=>c.createHash("sha1").update(s).digest("hex").slice(0,16);
const out={
 hls_id:[
  {infohash:"0123456789abcdef0123456789abcdef01234567",path:"Movie.2010.1080p/Movie.2010.1080p.mkv",size:8589934592,input:"0123456789abcdef0123456789abcdef01234567|Movie.2010.1080p/Movie.2010.1080p.mkv|8589934592"},
  {ad_id:"412345678",path:"Show.S01/Show.S01E02.mkv",size:1572864000,input:"ad:412345678|Show.S01/Show.S01E02.mkv|1572864000"}],
 sign:[{secret:"test-secret-do-not-use",exp:1767225600},{secret:"test-secret-do-not-use",exp:1767268800}],
 fingerprint:[2*MiB, 3*MiB, 10*MiB+12345].map(size=>({size}))
};
for(const h of out.hls_id)h.hls_id=hid(h.input);
for(const s of out.sign){s.hls_id=out.hls_id[0].hls_id;s.input=s.exp+s.hls_id+" "+s.secret;s.sig=sign(s.exp,s.hls_id,s.secret);}
for(const f of out.fingerprint){const b=gen(f.size);const s=f.size;
 f.mode=s<3*MiB?"whole":"three";
 if(f.mode==="three"){const m=Math.floor(s/2)-512*KiB;f.offsets=[0,m,s-MiB];f.chunk_sha256=f.offsets.map(o=>c.createHash("sha256").update(b.subarray(o,o+MiB)).digest("hex"));}
 f.fingerprint=fp(b);}
out.generator="byte[i] = uint32(i * 2654435761) >> 24, i — смещение в файле";
require("fs").writeFileSync(process.argv[2],JSON.stringify(out,null,2)+"\n");
console.log(JSON.stringify(out.fingerprint.map(f=>[f.size,f.chunk_sha256,f.fingerprint])));
// master.m3u8 подписывает основной сервер, в стиле makeMirrorToken (HMAC-SHA256, hex)
{ const fs=require("fs"),p=process.argv[2],v=JSON.parse(fs.readFileSync(p));
  const secret="test-session-secret-do-not-use",exp=1767225600,id=v.hls_id[0].hls_id,input=`hls:${exp}:${id}`;
  v.master_sign=[{session_secret:secret,exp,hls_id:id,input,sig:c.createHmac("sha256",secret).update(input).digest("hex")}];
  fs.writeFileSync(p,JSON.stringify(v,null,2)+"\n"); }
