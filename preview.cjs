const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assets = path.join(__dirname, 'app/src/main/assets');
const types = {'index.html':'text/html; charset=utf-8','app.css':'text/css; charset=utf-8','app.js':'application/javascript; charset=utf-8'};
http.createServer((req,res) => {
  const name = req.url === '/' ? 'index.html' : req.url.slice(1);
  if (!Object.hasOwn(types,name)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, {'Content-Type':types[name],'Cache-Control':'no-store'});
  res.end(fs.readFileSync(path.join(assets,name)));
}).listen(8769,'127.0.0.1', () => console.log('S9 preview: http://127.0.0.1:8769'));
