# Cloudflare Worker

Бесплатный способ проксирования — **не нужно покупать домен** (в отличие от CF-proxy). Вы разворачиваете
маленький воркер на своём аккаунте Cloudflare (`*.workers.dev`), который принимает WebSocket на
`/apiws?dst=<IP DC Telegram>` и делает сырой TCP-connect на `dst:443`, переливая байты в обе стороны.
Приложение отправляет через него ровно тот же обфусцированный MTProto-поток, что и в TCP-фолбэке —
только теперь трафик выходит к Telegram с edge-серверов Cloudflare, минуя блокировку IP-адресов Telegram.

CF Worker в приложении используется как **дополнительный тир фолбэка**: сначала пробуются прямые
web-front эндпоинты, затем CF-proxy домены, и только потом (если задан воркер-домен) — воркеры.

## Как развернуть

1. Создайте (или войдите) аккаунт на https://dash.cloudflare.com/ и подтвердите email.
2. Слева: `Compute` → `Workers & Pages` → `Create application` → `Start with Hello World!` → `Deploy`.
3. Откройте `Edit code`, замените весь код на код ниже, нажмите `Deploy`.
4. Скопируйте адрес воркера (вида `random-symbols-1234.username.workers.dev`) и вставьте его в
   настройках приложения в поле **Cloudflare Worker домен**. Можно указать несколько адресов через запятую.

Если сеть блокирует сам домен `workers.dev`, добавьте `cloudflare.com`, `cloudflare.dev`, `workers.dev`
в обход DPI (zapret / desync-VPN приложения).

## Код Worker'а

```javascript
import { connect } from "cloudflare:sockets";

function toBytes(data) {
	if (data instanceof ArrayBuffer) {
		return new Uint8Array(data);
	}
	if (typeof data === "string") {
		return new TextEncoder().encode(data);
	}
	if (data && typeof data.arrayBuffer === "function") {
		return data.arrayBuffer().then((ab) => new Uint8Array(ab));
	}
	return new Uint8Array();
}

export default {
	async fetch(request) {
		if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
			return new Response("Expected websocket", { status: 426 });
		}

		const url = new URL(request.url);
		if (url.pathname !== "/apiws") {
			return new Response("Not found", { status: 404 });
		}

		const dst = url.searchParams.get("dst");
		const pair = new WebSocketPair();
		const client = pair[0];
		const server = pair[1];
		server.accept();

		const socket = connect({ hostname: dst, port: 443 });
		const tcpReader = socket.readable.getReader();
		const tcpWriter = socket.writable.getWriter();

		server.addEventListener("message", async (event) => {
			try {
				await tcpWriter.write(await toBytes(event.data));
			} catch {
				try {
					server.close(1011, "tcp write failed");
				} catch {}
			}
		});

		server.addEventListener("close", async () => {
			try {
				await tcpWriter.close();
			} catch {}
			try {
				socket.close();
			} catch {}
		});

		(async () => {
			try {
				while (true) {
					const { value, done } = await tcpReader.read();
					if (done) {
						break;
					}
					if (value) {
						server.send(value);
					}
				}
			} catch {
			} finally {
				try {
					server.close();
				} catch {}
				try {
					tcpReader.releaseLock();
				} catch {}
				try {
					socket.close();
				} catch {}
			}
		})();

		return new Response(null, { status: 101, webSocket: client });
	},
};
```

_Идея воркера основана на подходе из upstream-проекта Flowseal/tg-ws-proxy._
