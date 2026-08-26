package mois.economy.balop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mois.economy.Economy;
import mois.economy.Money;
import mois.economy.data.EconomyDb;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据库管理前端（/balop start | stop）：本机 HTTP 服务器 + 单页管理面板。
 * <p>
 * 能力：
 * <ul>
 * <li>玩家资金增删改查（加钱/扣钱/设余额，允许余额为负——交易回滚可能造成负数）；</li>
 * <li>交易流水查询（按玩家/类型/渠道分页，含物品完整组件数据 item_data）；</li>
 * <li>交易记录删除与批量删除——删除时同步回滚资金：删 BUY 记录退款收回
 * （余额 +price），删 SELL 记录扣回所得（余额 -price），原子事务。</li>
 * </ul>
 * 监听地址/端口来自 config.json 的 balop 段（默认 localhost:8899，仅本机可访问）。
 * 注意：若配置为局域网/公网地址，任何能访问该端口的人都能改资金——请自行评估风险。
 * 所有 API 与页面均无鉴权。
 */
public final class BalopServer {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int DEFAULT_PAGE_SIZE = 20;

	private static HttpServer server;
	private static ExecutorService executor;
	private static String host;
	private static int port;

	private BalopServer() {
	}

	public static boolean isRunning() {
		return server != null;
	}

	/** 当前监听地址（未启动返回 null）。 */
	public static String address() {
		return server != null ? "http://" + host + ":" + port : null;
	}

	/** 启动 HTTP 服务器；失败返回错误信息（端口占用等），成功返回 null。 */
	public static synchronized String start(String listenHost, int listenPort) {
		if (server != null) {
			return "管理前端已在运行：" + address();
		}
		try {
			server = HttpServer.create(new InetSocketAddress(listenHost, listenPort), 0);
			executor = Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "balop-http");
				t.setDaemon(true);
				return t;
			});
			server.setExecutor(executor);
			server.createContext("/", BalopServer::handle);
			server.start();
			host = listenHost;
			port = listenPort;
			Economy.LOGGER.info("数据库管理前端已启动：http://{}:{}", listenHost, listenPort);
			return null;
		} catch (IOException e) {
			server = null;
			return "启动管理前端失败（" + e.getMessage() + "），请检查 config.json 中 balop.host/balop.port 是否被占用";
		}
	}

	/** 停止 HTTP 服务器。 */
	public static synchronized void stop() {
		if (server == null) {
			return;
		}
		server.stop(0);
		server = null;
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		Economy.LOGGER.info("数据库管理前端已停止");
	}

	// ---------- 路由 ----------

	private static void handle(HttpExchange exchange) throws IOException {
		try {
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod();
			if (path.equals("/") || path.equals("/index.html")) {
				html(exchange, 200, PAGE_HTML);
				return;
			}
			if (path.startsWith("/api/")) {
				handleApi(exchange, method, path);
				return;
			}
			json(exchange, 404, Map.of("error", "Not Found"));
		} catch (Exception e) {
			Economy.LOGGER.error("balop API 处理异常", e);
			json(exchange, 500, Map.of("error", "服务器内部错误：" + e.getMessage()));
		}
	}

	private static void handleApi(HttpExchange exchange, String method, String path) throws IOException {
		// ---- 玩家 ----
		if (path.equals("/api/players") && method.equals("GET")) {
			Map<String, String> q = query(exchange);
			String keyword = q.getOrDefault("q", "");
			int page = parseInt(q.getOrDefault("page", "1"), 1, 1, Integer.MAX_VALUE);
			int size = parseInt(q.getOrDefault("size", String.valueOf(DEFAULT_PAGE_SIZE)), DEFAULT_PAGE_SIZE, 1, 200);
			EconomyDb.AccountPage result = EconomyDb.listAccounts(keyword, size, (page - 1) * size);
			JsonObject body = new JsonObject();
			body.addProperty("total", result.total());
			body.addProperty("page", page);
			body.addProperty("size", size);
			JsonArray list = new JsonArray();
			for (EconomyDb.AccountEntry entry : result.list()) {
				JsonObject o = new JsonObject();
				o.addProperty("uuid", entry.uuid().toString());
				o.addProperty("name", entry.name());
				o.addProperty("balance", entry.balance());
				list.add(o);
			}
			body.add("list", list);
			json(exchange, 200, body);
			return;
		}
		// ---- 单个玩家的交易流水（必须先于「单个玩家」分支：其子路径含 /transactions） ----
		java.util.regex.Matcher playerTxMatch = java.util.regex.Pattern
				.compile("^/api/players/([0-9a-fA-F-]{36})/transactions$").matcher(path);
		if (playerTxMatch.matches() && method.equals("GET")) {
			UUID uuid = parseUuid(exchange, playerTxMatch.group(1));
			if (uuid == null) {
				return;
			}
			Map<String, String> q = query(exchange);
			String type = q.getOrDefault("type", "");
			String channel = q.getOrDefault("channel", "");
			int page = parseInt(q.getOrDefault("page", "1"), 1, 1, Integer.MAX_VALUE);
			int size = parseInt(q.getOrDefault("size", String.valueOf(DEFAULT_PAGE_SIZE)), DEFAULT_PAGE_SIZE, 1, 200);
			EconomyDb.TransactionPage result = EconomyDb.queryTransactions(uuid, type, channel, size, (page - 1) * size);
			JsonObject body = new JsonObject();
			body.addProperty("total", result.total());
			body.addProperty("page", page);
			body.addProperty("size", size);
			JsonArray list = new JsonArray();
			for (EconomyDb.TransactionEntry tx : result.list()) {
				list.add(transactionJson(tx));
			}
			body.add("list", list);
			json(exchange, 200, body);
			return;
		}
		// ---- 单个玩家（详情 / 资金增删改） ----
		java.util.regex.Matcher playerMatch = java.util.regex.Pattern
				.compile("^/api/players/([0-9a-fA-F-]{36})(?:/([a-z]+))?$").matcher(path);
		if (playerMatch.matches()) {
			UUID uuid = parseUuid(exchange, playerMatch.group(1));
			if (uuid == null) {
				return;
			}
			String sub = playerMatch.group(2);
			if (method.equals("GET") && sub == null) {
				json(exchange, 200, Map.of("uuid", uuid.toString(), "name",
						EconomyDb.accountName(uuid), "balance", EconomyDb.getBalance(uuid)));
				return;
			}
			// 资金操作：credit（加钱）/ deduct（扣钱，允许扣成负数）/ balance（设余额，可负）
			if (method.equals("POST") && sub != null) {
				JsonObject body = parseBody(exchange);
				if (body == null) {
					return;
				}
				Long amount = body.has("amountCents") ? safeLong(body.get("amountCents")) : null;
				if (amount == null) {
					json(exchange, 400, Map.of("error", "缺少 amountCents（整数分）"));
					return;
				}
				long newBalance;
				String name = EconomyDb.accountName(uuid);
				switch (sub) {
					case "credit" -> {
						if (amount < 0) {
							json(exchange, 400, Map.of("error", "加钱金额不能为负"));
							return;
						}
						newBalance = EconomyDb.adjustBalance(uuid, name, amount);
					}
					case "deduct" -> {
						if (amount < 0) {
							json(exchange, 400, Map.of("error", "扣钱金额不能为负"));
							return;
						}
						// 管理扣款允许扣成负数（回滚场景），与玩家侧 deduct 的余额检查不同
						newBalance = EconomyDb.adjustBalance(uuid, name, -amount);
					}
					case "balance" -> {
						EconomyDb.setBalance(uuid, name, amount);
						newBalance = EconomyDb.getBalance(uuid);
					}
					default -> {
						json(exchange, 404, Map.of("error", "未知操作"));
						return;
					}
				}
				json(exchange, 200, Map.of("uuid", uuid.toString(), "balance", newBalance));
				return;
			}
			json(exchange, 405, Map.of("error", "Method Not Allowed"));
			return;
		}
		// ---- 交易流水查询（uuid 可空 = 全部玩家） ----
		if (path.equals("/api/transactions") && method.equals("GET")) {
			Map<String, String> q = query(exchange);
			UUID uuid = null;
			if (q.containsKey("uuid") && !q.get("uuid").isEmpty()) {
				uuid = parseUuid(exchange, q.get("uuid"));
				if (uuid == null) {
					return;
				}
			}
			String type = q.getOrDefault("type", "");
			String channel = q.getOrDefault("channel", "");
			int page = parseInt(q.getOrDefault("page", "1"), 1, 1, Integer.MAX_VALUE);
			int size = parseInt(q.getOrDefault("size", String.valueOf(DEFAULT_PAGE_SIZE)), DEFAULT_PAGE_SIZE, 1, 200);
			EconomyDb.TransactionPage result = EconomyDb.queryTransactions(uuid, type, channel, size, (page - 1) * size);
			JsonObject body = new JsonObject();
			body.addProperty("total", result.total());
			body.addProperty("page", page);
			body.addProperty("size", size);
			JsonArray list = new JsonArray();
			for (EconomyDb.TransactionEntry tx : result.list()) {
				list.add(transactionJson(tx));
			}
			body.add("list", list);
			json(exchange, 200, body);
			return;
		}
		// ---- 删除单条交易记录（同步回滚资金） ----
		java.util.regex.Matcher txMatch = java.util.regex.Pattern
				.compile("^/api/transactions/(\\d+)$").matcher(path);
		if (txMatch.matches() && method.equals("DELETE")) {
			long id = Long.parseLong(txMatch.group(1));
			List<EconomyDb.RollbackResult> results =
					EconomyDb.deleteTransactionsWithRollback(List.of(id));
			if (results.isEmpty()) {
				json(exchange, 404, Map.of("error", "记录不存在"));
				return;
			}
			json(exchange, 200, rollbackJson(results.get(0)));
			return;
		}
		// ---- 批量删除交易记录（同步回滚资金） ----
		if (path.equals("/api/transactions/delete") && method.equals("POST")) {
			JsonObject body = parseBody(exchange);
			if (body == null) {
				return;
			}
			JsonArray idsJson = body.has("ids") && body.get("ids").isJsonArray()
					? body.getAsJsonArray("ids") : null;
			if (idsJson == null || idsJson.isEmpty()) {
				json(exchange, 400, Map.of("error", "缺少 ids（交易记录 id 数组）"));
				return;
			}
			List<Long> ids = new ArrayList<>();
			for (var el : idsJson) {
				Long id = safeLong(el);
				if (id == null) {
					json(exchange, 400, Map.of("error", "ids 含非法 id"));
					return;
				}
				ids.add(id);
			}
			List<EconomyDb.RollbackResult> results = EconomyDb.deleteTransactionsWithRollback(ids);
			JsonObject body2 = new JsonObject();
			body2.addProperty("deleted", results.size());
			JsonArray list = new JsonArray();
			for (EconomyDb.RollbackResult r : results) {
				list.add(rollbackJson(r));
			}
			body2.add("results", list);
			json(exchange, 200, body2);
			return;
		}
		json(exchange, 404, Map.of("error", "Not Found"));
	}

	private static JsonObject rollbackJson(EconomyDb.RollbackResult r) {
		JsonObject o = new JsonObject();
		o.addProperty("id", r.id());
		o.addProperty("uuid", r.uuid().toString());
		o.addProperty("name", r.name());
		o.addProperty("type", r.type());
		o.addProperty("price", r.price());
		o.addProperty("balance", r.newBalance());
		return o;
	}

	private static JsonObject transactionJson(EconomyDb.TransactionEntry tx) {
		JsonObject o = new JsonObject();
		o.addProperty("id", tx.id());
		o.addProperty("uuid", tx.uuid().toString());
		o.addProperty("name", tx.name());
		o.addProperty("type", tx.type());
		o.addProperty("channel", tx.channel());
		o.addProperty("itemId", tx.itemId());
		o.addProperty("itemName", tx.itemName());
		if (tx.itemData() != null) {
			o.addProperty("itemData", tx.itemData());
		}
		o.addProperty("count", tx.count());
		o.addProperty("price", tx.price());
		o.addProperty("balance", tx.balance());
		o.addProperty("time", tx.time());
		return o;
	}

	// ---------- HTTP 工具 ----------

	private static Map<String, String> query(HttpExchange exchange) {
		Map<String, String> result = new java.util.HashMap<>();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw == null || raw.isEmpty()) {
			return result;
		}
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq < 0) {
				continue;
			}
			String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
			String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			result.put(key, value);
		}
		return result;
	}

	private static JsonObject parseBody(HttpExchange exchange) throws IOException {
		String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		try {
			return JsonParser.parseString(raw.isEmpty() ? "{}" : raw).getAsJsonObject();
		} catch (RuntimeException e) {
			json(exchange, 400, Map.of("error", "请求体不是合法 JSON"));
			return null;
		}
	}

	private static UUID parseUuid(HttpExchange exchange, String value) throws IOException {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			json(exchange, 400, Map.of("error", "非法 UUID：" + value));
			return null;
		}
	}

	private static int parseInt(String value, int def, int min, int max) {
		try {
			int v = Integer.parseInt(value.trim());
			return Math.max(min, Math.min(max, v));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static Long safeLong(com.google.gson.JsonElement el) {
		try {
			return el.getAsLong();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void json(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static void html(HttpExchange exchange, int status, String page) throws IOException {
		byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	// ---------- 管理页面 ----------

	private static final String PAGE_HTML = """
			<!DOCTYPE html>
			<html lang="zh-CN">
			<head>
			<meta charset="utf-8">
			<meta name="viewport" content="width=device-width, initial-scale=1">
			<title>Economy 数据库管理</title>
			<style>
			  :root { --bg:#0f1115; --card:#171a21; --border:#2a2f3a; --text:#e6e8ee; --muted:#8b93a3;
			          --accent:#4f8cff; --buy:#3fb950; --sell:#f0883e; --danger:#f85149; }
			  * { box-sizing:border-box; }
			  body { margin:0; background:var(--bg); color:var(--text);
			         font:14px/1.6 "Segoe UI", "Microsoft YaHei", sans-serif; }
			  .wrap { max-width:1200px; margin:0 auto; padding:20px; }
			  h1 { font-size:20px; margin:0 0 16px; }
			  h2 { font-size:15px; margin:20px 0 10px; border-bottom:1px solid var(--border); padding-bottom:6px; }
			  .card { background:var(--card); border:1px solid var(--border); border-radius:8px; padding:14px; margin-bottom:14px; }
			  input, select, button { background:#0d1016; color:var(--text); border:1px solid var(--border);
			    border-radius:6px; padding:6px 10px; font-size:13px; }
			  button { cursor:pointer; }
			  button:hover { border-color:var(--accent); }
			  button.danger { color:var(--danger); border-color:var(--danger); }
			  button.primary { background:var(--accent); border-color:var(--accent); color:#fff; }
			  table { width:100%; border-collapse:collapse; }
			  th, td { text-align:left; padding:6px 8px; border-bottom:1px solid var(--border); font-size:13px; }
			  th { color:var(--muted); font-weight:600; white-space:nowrap; }
			  tr.clickable { cursor:pointer; }
			  tr.clickable:hover { background:#1c212c; }
			  tr.selected { background:#1d2735; }
			  .muted { color:var(--muted); }
			  .right { text-align:right; }
			  .badge { display:inline-block; padding:1px 8px; border-radius:10px; font-size:12px; font-weight:600; }
			  .badge.buy { background:rgba(63,185,80,.15); color:var(--buy); }
			  .badge.sell { background:rgba(240,136,62,.15); color:var(--sell); }
			  .badge.neg { background:rgba(248,81,73,.15); color:var(--danger); }
			  .row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
			  .grow { flex:1; }
			  .pager { display:flex; gap:6px; align-items:center; margin-top:10px; }
			  details { margin:4px 0; }
			  summary { cursor:pointer; color:var(--muted); font-size:12px; }
			  pre { background:#0d1016; border:1px solid var(--border); border-radius:6px; padding:8px;
			    font-size:11px; overflow:auto; max-height:220px; white-space:pre-wrap; word-break:break-all; }
			  .toast { position:fixed; right:16px; bottom:16px; background:#1c212c; border:1px solid var(--border);
			    border-radius:8px; padding:10px 14px; max-width:420px; box-shadow:0 4px 16px #000a; }
			  .toast.ok { border-color:var(--buy); } .toast.err { border-color:var(--danger); }
			</style>
			</head>
			<body>
			<div class="wrap">
			  <h1>Economy 数据库管理</h1>
			  <div class="card">
			    <div class="row">
			      <input id="q" class="grow" placeholder="搜索玩家（名字 / UUID）" oninput="state.q=this.value; state.page=1; loadPlayers()">
			      <button onclick="loadPlayers()">搜索</button>
			    </div>
			    <table>
			      <thead><tr><th>名字</th><th>UUID</th><th class="right">余额</th></tr></thead>
			      <tbody id="playerRows"></tbody>
			    </table>
			    <div class="pager">
			      <button onclick="pagePlayers(-1)">上一页</button>
			      <span class="muted" id="playerPageInfo"></span>
			      <button onclick="pagePlayers(1)">下一页</button>
			    </div>
			  </div>
			  <div id="detail" style="display:none">
			    <div class="card">
			      <h2>玩家：<span id="detailName"></span>　余额：<span id="detailBalance"></span></h2>
			      <div class="row">
			        <input id="amountInput" placeholder="金额（元，可小数）" style="width:160px">
			        <button class="primary" onclick="operate('credit')">加钱</button>
			        <button onclick="operate('deduct')">扣钱</button>
			        <button onclick="operate('balance')">设为余额（可负）</button>
			      </div>
			      <div class="row" style="margin-top:8px">
			        <select id="txType" onchange="state.txType=this.value; state.txPage=1; loadTx()">
			          <option value="">全部类型</option><option value="BUY">BUY 购买</option><option value="SELL">SELL 出售</option>
			        </select>
			        <select id="txChannel" onchange="state.txChannel=this.value; state.txPage=1; loadTx()">
			          <option value="">全部渠道</option><option value="SHOP">SHOP 商店</option><option value="BM">BM 便捷购买</option>
			        </select>
			        <button onclick="selectAll()">全选</button>
			        <button class="danger" onclick="batchDelete()">批量删除选中（回滚资金）</button>
			      </div>
			      <table>
			        <thead><tr>
			          <th><input type="checkbox" id="checkAll" onchange="toggleAll(this.checked)"></th>
			          <th>ID</th><th>时间</th><th>类型</th><th>渠道</th><th>物品</th>
			          <th class="right">数量</th><th class="right">金额</th><th class="right">交易后余额</th><th>操作</th>
			        </tr></thead>
			        <tbody id="txRows"></tbody>
			      </table>
			      <div class="pager">
			        <button onclick="pageTx(-1)">上一页</button>
			        <span class="muted" id="txPageInfo"></span>
			        <button onclick="pageTx(1)">下一页</button>
			      </div>
			    </div>
			  </div>
			</div>
			<script>
			const state = { q:'', page:1, size:20, txPage:1, txSize:20, txType:'', txChannel:'', currentUuid:null, selected:new Set() };
			const $ = id => document.getElementById(id);
			const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
			const yuan = c => (c/100).toFixed(2);
			function cents(s){ s=String(s).trim(); if(!/^-?\\d+(\\.\\d{1,2})?$/.test(s)) throw new Error('金额格式错误');
			  const neg=s.startsWith('-'); const p=(neg?s.slice(1):s).split('.');
			  return (neg?-1:1)*(parseInt(p[0]||'0',10)*100+(p[1]?parseInt(p[1].padEnd(2,'0'),10):0)); }
			function fmtTime(t){ const d=new Date(t); const p=n=>String(n).padStart(2,'0');
			  return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds()); }
			async function api(path, opts){ const res=await fetch(path, opts); let data=null;
			  try{ data=await res.json(); }catch(e){}
			  if(!res.ok) throw new Error((data&&data.error)||('HTTP '+res.status)); return data; }
			function toast(msg, ok){ const t=document.createElement('div'); t.className='toast '+(ok?'ok':'err');
			  t.textContent=msg; document.body.appendChild(t); setTimeout(()=>t.remove(), 4000); }
			async function loadPlayers(){ try{
			  const d=await api('/api/players?q='+encodeURIComponent(state.q)+'&page='+state.page+'&size='+state.size);
			  const rows=$('playerRows'); rows.innerHTML='';
			  d.list.forEach(p=>{ const tr=document.createElement('tr'); tr.className='clickable';
			    if(state.currentUuid===p.uuid) tr.className+=' selected';
			    tr.onclick=()=>openPlayer(p.uuid, p.name);
			    tr.innerHTML='<td>'+esc(p.name)+'</td><td class="muted">'+esc(p.uuid)+'</td><td class="right">'+
			      (p.balance<0?'<span class="badge neg">'+esc(yuan(p.balance))+' 元</span>':esc(yuan(p.balance))+' 元')+'</td>';
			    rows.appendChild(tr); });
			  const totalPages=Math.max(1, Math.ceil(d.total/d.size));
			  $('playerPageInfo').textContent='第 '+d.page+'/'+totalPages+' 页，共 '+d.total+' 个账户';
			  if(d.list.length===0) rows.innerHTML='<tr><td colspan="3" class="muted">无账户</td></tr>';
			  }catch(e){ toast(e.message,false); } }
			function pagePlayers(d){ const np=state.page+d; if(np<1) return; state.page=np; loadPlayers(); }
			async function openPlayer(uuid, name){
			  state.currentUuid=uuid; state.txPage=1; state.selected.clear(); $('checkAll').checked=false;
			  $('detail').style.display=''; $('detailName').textContent=name;
			  $('detailBalance').textContent=yuan(await loadBalance(uuid))+' 元';
			  document.querySelectorAll('#playerRows tr').forEach(tr=>tr.classList.remove('selected'));
			  loadPlayers(); loadTx();
			}
			async function loadBalance(uuid){ const d=await api('/api/players/'+uuid); return d.balance; }
			async function operate(op){ const input=$('amountInput'); let amount;
			  try{ amount=cents(input.value); }catch(e){ toast(e.message,false); return; }
			  if(!state.currentUuid) return;
			  try{ const d=await api('/api/players/'+state.currentUuid+'/'+op, {method:'POST',
			    headers:{'Content-Type':'application/json'}, body:JSON.stringify({amountCents:amount})});
			    $('detailBalance').textContent=yuan(d.balance)+' 元'; toast('操作成功，余额 '+yuan(d.balance)+' 元', true);
			    input.value=''; loadPlayers(); }
			  catch(e){ toast(e.message,false); } }
			async function loadTx(){ if(!state.currentUuid) return; try{
			  const d=await api('/api/players/'+state.currentUuid+'/transactions?type='+state.txType+'&channel='+state.txChannel+
			    '&page='+state.txPage+'&size='+state.txSize);
			  const rows=$('txRows'); rows.innerHTML='';
			  d.list.forEach(tx=>{ const tr=document.createElement('tr');
			    tr.innerHTML='<td><input type="checkbox" data-id="'+tx.id+'" '+(state.selected.has(tx.id)?'checked':'')+
			      ' onchange="toggleOne('+tx.id+', this.checked)"></td>'+
			      '<td>'+tx.id+'</td><td class="muted">'+fmtTime(tx.time)+'</td>'+
			      '<td><span class="badge '+(tx.type==='BUY'?'buy':'sell')+'">'+tx.type+'</span></td>'+
			      '<td>'+esc(tx.channel)+'</td>'+
			      '<td>'+esc(tx.itemName)+'<details><summary>物品数据 (NBT/组件)</summary><pre>'+esc(tx.itemData||'(旧记录无完整数据)')+'</pre></details></td>'+
			      '<td class="right">'+tx.count+'</td>'+
			      '<td class="right">'+esc(yuan(tx.price))+' 元</td>'+
			      '<td class="right">'+(tx.balance<0?'<span class="badge neg">':'')+esc(yuan(tx.balance))+(tx.balance<0?'</span>':'')+'</td>'+
			      '<td><button class="danger" onclick="delOne('+tx.id+')">删除</button></td>';
			    rows.appendChild(tr); });
			  const totalPages=Math.max(1, Math.ceil(d.total/d.size));
			  $('txPageInfo').textContent='第 '+d.page+'/'+totalPages+' 页，共 '+d.total+' 条';
			  if(d.list.length===0) rows.innerHTML='<tr><td colspan="10" class="muted">无交易记录</td></tr>';
			  }catch(e){ toast(e.message,false); } }
			function pageTx(d){ const np=state.txPage+d; if(np<1) return; state.txPage=np; loadTx(); }
			function toggleOne(id, checked){ if(checked) state.selected.add(id); else state.selected.delete(id); }
			function toggleAll(checked){ document.querySelectorAll('#txRows input[data-id]').forEach(el=>{
			    el.checked=checked; toggleOne(Number(el.dataset.id), checked); }); }
			function selectAll(){ const ids=[]; document.querySelectorAll('#txRows input[data-id]').forEach(el=>ids.push(Number(el.dataset.id)));
			  ids.forEach(id=>state.selected.add(id)); document.querySelectorAll('#txRows input[data-id]').forEach(el=>el.checked=true); }
			function rollbackMsg(r){ const dir = r.type==='BUY' ? '退款收回 +'+yuan(r.price) : '扣回所得 -'+yuan(r.price);
			  return '已删除 #'+r.id+'（'+r.type+' '+esc(r.name)+'），'+dir+' 元，新余额 '+yuan(r.balance)+' 元'; }
			async function delOne(id){ if(!confirm('删除交易记录 #'+id+' 并回滚资金？')) return; try{
			  const r=await api('/api/transactions/'+id, {method:'DELETE'});
			  toast(rollbackMsg(r), true); state.selected.delete(id); loadTx(); loadPlayers(); refreshBalance(); }
			  catch(e){ toast(e.message,false); } }
			async function batchDelete(){ if(state.selected.size===0){ toast('请先勾选要删除的记录', false); return; }
			  if(!confirm('批量删除 '+state.selected.size+' 条交易记录并回滚资金？')) return; try{
			  const d=await api('/api/transactions/delete', {method:'POST', headers:{'Content-Type':'application/json'},
			    body:JSON.stringify({ids:[...state.selected]})});
			  toast('已删除 '+d.deleted+' 条，其中 '+d.results.map(r=>esc(r.name)+' '+(r.type==='BUY'?'+':'')+yuan(r.price)+' 元').join('；'), true);
			  state.selected.clear(); $('checkAll').checked=false; loadTx(); loadPlayers(); refreshBalance(); }
			  catch(e){ toast(e.message,false); } }
			async function refreshBalance(){ if(state.currentUuid){ try{
			  $('detailBalance').textContent=yuan(await loadBalance(state.currentUuid))+' 元'; }catch(e){} } }
			loadPlayers();
			</script>
			</body>
			</html>
			""";
}
