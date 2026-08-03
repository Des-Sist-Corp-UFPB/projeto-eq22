// Custom server instead of `next start`: this process terminates the browser's TCP connection
// directly (nothing sits in front of it in this deployment), so the socket peer is the real
// client. next.config.ts's rewrites() forward request headers as-is when proxying /api to the
// backend but never sets X-Forwarded-For itself, so without this the backend would see every
// browser request as coming from this container. Any client-supplied X-Forwarded-For is
// overwritten rather than trusted, since the browser is not a proxy.
import { createServer } from "node:http";
import next from "next";

const port = Number(process.env.PORT) || 3000;
const app = next({ dev: process.env.NODE_ENV !== "production" });
const handle = app.getRequestHandler();

app.prepare().then(() => {
  createServer((req, res) => {
    req.headers["x-forwarded-for"] = req.socket.remoteAddress;
    handle(req, res);
  }).listen(port, () => {
    console.log(`> Ready on port ${port}`);
  });
});
