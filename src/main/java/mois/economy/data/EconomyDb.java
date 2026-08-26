package mois.economy.data;

import mois.economy.Economy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite 资金存储。余额一律以整数“分”存储（BIGINT），避免浮点误差。
 * 只有玩家账户（服务器公共账户已移除；历史遗留的全零 UUID 账户行在
 * 排行榜/总资产统计中排除）。所有命令均在服务端主线程执行，这里仍对
 * 每个操作加 synchronized 作为兜底。
 */
public final class EconomyDb {
	/** 历史遗留的服务器公共账户 UUID（全零），统计与排行榜中排除。 */
	private static final UUID LEGACY_SERVER_ACCOUNT_UUID = new UUID(0L, 0L);

	private static final String KEY_ANNOUNCEMENT = "announcement";

	// ---------- 交易记录（买卖流水） ----------

	/** 交易类型：购买（花钱获得物品，删除记录时退款收回 +price）。 */
	public static final String TYPE_BUY = "BUY";
	/** 交易类型：出售（物品消失换钱，删除记录时扣回所得 -price）。 */
	public static final String TYPE_SELL = "SELL";
	/** 交易类型：转账收入（/pay）。 */
	public static final String TYPE_TRANSFER_IN = "TRANSFER_IN";
	/** 交易类型：转账支出（/pay）。 */
	public static final String TYPE_TRANSFER_OUT = "TRANSFER_OUT";
	/** 交易类型：管理员加钱（/eco add、管理面板）。 */
	public static final String TYPE_ADMIN_ADD = "ADMIN_ADD";
	/** 交易类型：管理员扣钱（/eco remove、管理面板）。 */
	public static final String TYPE_ADMIN_SUB = "ADMIN_SUB";
	/** 交易类型：管理员设置余额（/eco set、管理面板）。 */
	public static final String TYPE_ADMIN_SET = "ADMIN_SET";
	/** 交易类型：系统扣费（飞行/传送等）。 */
	public static final String TYPE_FEE = "FEE";
	/** 交易类型：发出红包。 */
	public static final String TYPE_REDPACKET_SEND = "REDPACKET_SEND";
	/** 交易类型：领取红包。 */
	public static final String TYPE_REDPACKET_CLAIM = "REDPACKET_CLAIM";
	/** 交易类型：红包过期返还。 */
	public static final String TYPE_REDPACKET_REFUND = "REDPACKET_REFUND";
	/** 交易渠道：/bm 便捷购买。 */
	public static final String CHANNEL_BM = "BM";
	/** 交易渠道：/shop 箱子商店自动出售。 */
	public static final String CHANNEL_SHOP = "SHOP";
	/** 交易渠道：/pay 转账。 */
	public static final String CHANNEL_PAY = "PAY";
	/** 交易渠道：/eco 管理员指令。 */
	public static final String CHANNEL_ECO = "ECO";
	/** 交易渠道：管理前端（/balop）。 */
	public static final String CHANNEL_BALOP = "BALOP";
	/** 交易渠道：飞行扣费。 */
	public static final String CHANNEL_FLY = "FLY";
	/** 交易渠道：传送扣费。 */
	public static final String CHANNEL_TP = "TP";
	/** 交易渠道：/buy 指令购买。 */
	public static final String CHANNEL_BUY = "BUY";
	/** 交易渠道：红包。 */
	public static final String CHANNEL_REDPACKET = "REDPACKET";

	private static Connection connection;
	private static Path dbPath;

	private EconomyDb() {
	}

	public static boolean isOpen() {
		return connection != null;
	}

	public static Path getDbPath() {
		return dbPath;
	}

	public static void open(Path path) {
		if (connection != null) {
			return;
		}
		try {
			// 显式加载驱动：嵌套 jar 环境下不依赖 ServiceLoader 自动发现。
			Class.forName("org.sqlite.JDBC");
			connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA journal_mode=WAL");
				statement.execute("PRAGMA busy_timeout=5000");
			}
			initSchema();
			runSelfTest();
			dbPath = path;
			Economy.LOGGER.info("SQLite 数据库已就绪：{}", path);
		} catch (Exception e) {
			Economy.LOGGER.error("SQLite 数据库初始化失败", e);
			close();
		}
	}

	public static void close() {
		if (connection == null) {
			return;
		}
		try {
			connection.close();
		} catch (SQLException ignored) {
			// 关闭失败无可补救，忽略。
		} finally {
			connection = null;
		}
	}

	private static void initSchema() throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE IF NOT EXISTS economy_accounts (
						uuid TEXT PRIMARY KEY,
						name TEXT NOT NULL,
						balance INTEGER NOT NULL DEFAULT 0
					)""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS economy_settings (
						key TEXT PRIMARY KEY,
						value TEXT NOT NULL
					)""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS homes (
						uuid TEXT NOT NULL,
						name TEXT NOT NULL,
						world TEXT NOT NULL,
						x REAL NOT NULL,
						y REAL NOT NULL,
						z REAL NOT NULL,
						created INTEGER NOT NULL,
						PRIMARY KEY (uuid, name)
					)""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS back_points (
						uuid TEXT PRIMARY KEY,
						world TEXT NOT NULL,
						x REAL NOT NULL,
						y REAL NOT NULL,
						z REAL NOT NULL
					)""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS economy_transactions (
						id INTEGER PRIMARY KEY AUTOINCREMENT,
						uuid TEXT NOT NULL,
						name TEXT NOT NULL,
						type TEXT NOT NULL,
						channel TEXT NOT NULL,
						item_id TEXT NOT NULL,
						item_name TEXT NOT NULL,
						item_data TEXT,
						count INTEGER NOT NULL,
						price INTEGER NOT NULL,
						balance INTEGER NOT NULL,
						time INTEGER NOT NULL
					)""");
			statement.execute("""
					CREATE INDEX IF NOT EXISTS idx_transactions_uuid_time
						ON economy_transactions (uuid, time DESC)
					""");
			migrateSchema();
		}
	}

	/**
	 * 旧库迁移：
	 * <ol>
	 * <li>economy_accounts 移除 CHECK (balance &gt;= 0)——管理前端删除交易记录回滚时
	 * 允许余额为负（SQLite 无法直接改约束，重建表拷贝数据）；</li>
	 * <li>economy_transactions 补充 item_data 列（物品完整组件数据，旧记录为 NULL）。</li>
	 * </ol>
	 */
	private static void migrateSchema() throws SQLException {
		// 1) 账户表去 CHECK
		String accountsSql = null;
		try (Statement st = connection.createStatement();
			 ResultSet rs = st.executeQuery(
					 "SELECT sql FROM sqlite_master WHERE type='table' AND name='economy_accounts'")) {
			if (rs.next()) {
				accountsSql = rs.getString(1);
			}
		}
		if (accountsSql != null && accountsSql.toUpperCase().contains("CHECK")) {
			try (Statement st = connection.createStatement()) {
				st.execute("ALTER TABLE economy_accounts RENAME TO economy_accounts_old");
				st.execute("""
						CREATE TABLE economy_accounts (
							uuid TEXT PRIMARY KEY,
							name TEXT NOT NULL,
							balance INTEGER NOT NULL DEFAULT 0
						)""");
				st.execute("""
						INSERT INTO economy_accounts (uuid, name, balance)
						SELECT uuid, name, balance FROM economy_accounts_old
						""");
				st.execute("DROP TABLE economy_accounts_old");
			}
			Economy.LOGGER.info("数据库迁移：economy_accounts 已移除余额非负约束");
		}
		// 2) 交易流水补 item_data 列
		boolean hasItemData = false;
		try (Statement st = connection.createStatement();
			 ResultSet rs = st.executeQuery("PRAGMA table_info(economy_transactions)")) {
			while (rs.next()) {
				if ("item_data".equals(rs.getString(2))) {
					hasItemData = true;
				}
			}
		}
		if (!hasItemData) {
			try (Statement st = connection.createStatement()) {
				st.execute("ALTER TABLE economy_transactions ADD COLUMN item_data TEXT");
			}
			Economy.LOGGER.info("数据库迁移：economy_transactions 已补充 item_data 列");
		}
	}

	/** 数据库自检：写入、读取、转账、余额不足拦截各验证一次，随后清理测试数据。 */
	private static void runSelfTest() throws SQLException {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		try {
			ensureAccount(a, "自检A");
			ensureAccount(b, "自检B");
			long amount = 123456789L; // 1234567.89 元，验证整数分精度
			credit(a, "自检A", amount);
			if (getBalance(a) != amount) {
				throw new IllegalStateException("余额写入/读取不一致");
			}
			// 入账一个不存在的账户：应自动建行并使用传入的名字（修复 add 无效与未知玩家）。
			UUID c = UUID.randomUUID();
			credit(c, "自检C", 500L);
			if (getBalance(c) != 500L) {
				throw new IllegalStateException("新账户入账失败");
			}
			if (!topAccounts(100, 0).stream().anyMatch(e -> e.uuid().equals(c) && e.name().equals("自检C"))) {
				throw new IllegalStateException("新账户名字未正确存储");
			}
			deleteAccount(c);
			if (!transfer(a, b, "自检B", 99999L)) {
				throw new IllegalStateException("充足余额转账被拒绝");
			}
			if (getBalance(a) != amount - 99999L || getBalance(b) != 99999L) {
				throw new IllegalStateException("转账后余额不一致");
			}
			if (!topAccounts(100, 0).stream().anyMatch(e -> e.uuid().equals(b) && e.name().equals("自检B"))) {
				throw new IllegalStateException("转账目标名字被覆盖");
			}
			if (transfer(b, a, "自检A", 99999L + 1)) {
				throw new IllegalStateException("余额不足的转账未被拦截");
			}
			setBalance(a, "自检A", 1000L);
			if (getBalance(a) != 1000L) {
				throw new IllegalStateException("设置余额不一致");
			}
			if (!deduct(a, 300L)) {
				throw new IllegalStateException("充足余额的扣款被拒绝");
			}
			if (getBalance(a) != 700L) {
				throw new IllegalStateException("扣款后余额不一致");
			}
			if (deduct(a, 701L)) {
				throw new IllegalStateException("余额不足的扣款未被拦截");
			}
			// 交易流水：写入后按时间倒序可查回，且余额字段为写入时余额；
			// item_data（物品完整组件数据）一并写入与读回
			recordTransaction(a, "自检A", TYPE_SELL, CHANNEL_SHOP, "minecraft:diamond", "钻石",
					"{\"id\":\"minecraft:diamond\",\"count\":3}", 3, 1500L);
			recordTransaction(a, "自检A", TYPE_BUY, CHANNEL_BM, "minecraft:stone", "石头",
					"{\"id\":\"minecraft:stone\",\"count\":10}", 10, 200L);
			List<TransactionEntry> tx = recentTransactions(a, 10);
			if (tx.size() != 2 || !tx.get(0).type().equals(TYPE_BUY)
					|| tx.get(0).count() != 10 || tx.get(0).price() != 200L
					|| tx.get(0).balance() != getBalance(a)
					|| !tx.get(1).channel().equals(CHANNEL_SHOP)
					|| !tx.get(1).itemData().contains("minecraft:diamond")) {
				throw new IllegalStateException("交易流水写入/读取不一致");
			}
			if (transactionCount(a) != 2) {
				throw new IllegalStateException("交易流水计数不一致");
			}
			// 负余额：管理回滚允许余额为负（setBalance 与 adjustBalance 均放行）
			setBalance(a, "自检A", -500L);
			if (getBalance(a) != -500L) {
				throw new IllegalStateException("负余额设置失败");
			}
			adjustBalance(a, "自检A", 300L);
			if (getBalance(a) != -200L) {
				throw new IllegalStateException("负余额调整失败");
			}
			setBalance(a, "自检A", 700L);
			// 删除交易记录回滚：删 SELL 扣回所得（700-1500=-800），删 BUY 退回花费
			List<RollbackResult> rollbacks = deleteTransactionsWithRollback(List.of(tx.get(1).id()));
			if (rollbacks.size() != 1 || !rollbacks.get(0).type().equals(TYPE_SELL)
					|| rollbacks.get(0).newBalance() != -800L) {
				throw new IllegalStateException("删除交易记录回滚失败");
			}
			rollbacks = deleteTransactionsWithRollback(List.of(tx.get(0).id()));
			if (rollbacks.size() != 1 || !rollbacks.get(0).type().equals(TYPE_BUY)
					|| rollbacks.get(0).newBalance() != -600L) {
				throw new IllegalStateException("删除交易记录回滚失败");
			}
			if (transactionCount(a) != 0) {
				throw new IllegalStateException("删除交易记录后流水未清空");
			}
			setBalance(a, "自检A", 700L);
			// 非买卖流水（转账/管理/扣费等）：记录 + 删除仅删记录、不回滚资金
			recordMoneyLog(a, "自检A", TYPE_TRANSFER_OUT, CHANNEL_PAY, "转账测试", -100L);
			List<TransactionEntry> logs = recentTransactions(a, 10);
			TransactionEntry logEntry = logs.stream()
					.filter(t -> t.type().equals(TYPE_TRANSFER_OUT)).findFirst().orElse(null);
			if (logEntry == null || logEntry.price() != -100L) {
				throw new IllegalStateException("非买卖流水写入/读取不一致");
			}
			long balanceBeforeDelete = getBalance(a);
			rollbacks = deleteTransactionsWithRollback(List.of(logEntry.id()));
			if (rollbacks.size() != 1 || getBalance(a) != balanceBeforeDelete) {
				throw new IllegalStateException("非买卖流水删除不应回滚资金");
			}
		} finally {
			deleteAccount(a);
			deleteAccount(b);
		}
		Economy.LOGGER.info("数据库自检通过");
	}

	// ---------- 账户 ----------

	public static synchronized long getBalance(UUID uuid) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT balance FROM economy_accounts WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getLong(1);
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询余额失败", e);
		}
		return 0L;
	}

	/** 查询账户名（管理前端显示用）；账户不存在返回“未知玩家”。 */
	public static synchronized String accountName(UUID uuid) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT name FROM economy_accounts WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getString(1);
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询账户名失败", e);
		}
		return "未知玩家";
	}

	public static synchronized void ensureAccount(UUID uuid, String name) {
		requireOpen();
		if (name == null) {
			name = "未知玩家";
		}
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO economy_accounts (uuid, name, balance) VALUES (?, ?, 0)
				ON CONFLICT(uuid) DO UPDATE SET name = excluded.name
				""")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("创建账户失败", e);
		}
	}

	/** 入账（无来源），例如服务器资产初始资金；账户不存在时按给定名字创建。 */
	public static synchronized void credit(UUID uuid, String name, long amount) {
		requireOpen();
		if (amount < 0) {
			throw new IllegalArgumentException("入账金额不能为负");
		}
		ensureAccount(uuid, name);
		try (PreparedStatement ps = connection.prepareStatement("""
				UPDATE economy_accounts SET balance = balance + ? WHERE uuid = ?
				""")) {
			ps.setLong(1, amount);
			ps.setString(2, uuid.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("入账失败", e);
		}
	}

	/** 单笔扣款；余额不足时返回 false 且不做任何修改。 */
	public static synchronized boolean deduct(UUID uuid, long amount) {
		requireOpen();
		if (amount < 0) {
			throw new IllegalArgumentException("扣款金额不能为负");
		}
		try {
			return deductRow(uuid, amount);
		} catch (SQLException e) {
			throw new DatabaseException("扣款失败", e);
		}
	}

	/** 向多个账户各扣 amount，原子执行：任一余额不足则整体不做修改。 */
	public static synchronized boolean deductMany(List<UUID> targets, long amount) {
		requireOpen();
		if (amount < 0) {
			throw new IllegalArgumentException("扣款金额不能为负");
		}
		if (targets.isEmpty()) {
			return true;
		}
		boolean oldAutoCommit;
		try {
			oldAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
		} catch (SQLException e) {
			throw new DatabaseException("开启事务失败", e);
		}
		try {
			for (UUID uuid : targets) {
				if (!deductRow(uuid, amount)) {
					connection.rollback();
					return false;
				}
			}
			connection.commit();
			return true;
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
				// 回滚失败时交由上层异常处理。
			}
			throw new DatabaseException("批量扣款失败", e);
		} finally {
			try {
				connection.setAutoCommit(oldAutoCommit);
			} catch (SQLException e) {
				throw new DatabaseException("恢复自动提交失败", e);
			}
		}
	}

	private static boolean deductRow(UUID uuid, long amount) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
				UPDATE economy_accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?
				""")) {
			ps.setLong(1, amount);
			ps.setString(2, uuid.toString());
			ps.setLong(3, amount);
			return ps.executeUpdate() == 1;
		}
	}

	/**
	 * 把账户余额直接设置为指定值（允许负数——管理前端删除交易记录回滚后
	 * 余额可能为负）；账户不存在时先创建。
	 */
	public static synchronized void setBalance(UUID uuid, String name, long balance) {
		requireOpen();
		ensureAccount(uuid, name);
		try (PreparedStatement ps = connection.prepareStatement("""
				UPDATE economy_accounts SET balance = ? WHERE uuid = ?
				""")) {
			ps.setLong(1, balance);
			ps.setString(2, uuid.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("设置余额失败", e);
		}
	}

	/**
	 * 直接调整余额（delta 可为负，允许余额变负）：管理前端增删资金与
	 * 交易记录回滚用。返回调整后的余额。
	 */
	public static synchronized long adjustBalance(UUID uuid, String name, long delta) {
		requireOpen();
		ensureAccount(uuid, name);
		try (PreparedStatement ps = connection.prepareStatement("""
				UPDATE economy_accounts SET balance = balance + ? WHERE uuid = ?
				""")) {
			ps.setLong(1, delta);
			ps.setString(2, uuid.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("调整余额失败", e);
		}
		return getBalance(uuid);
	}

	/** 单笔转账；目标账户不存在时按 toName 创建。余额不足时返回 false 且不做任何修改。 */
	public static synchronized boolean transfer(UUID from, UUID to, String toName, long amount) {
		requireOpen();
		if (amount < 0) {
			throw new IllegalArgumentException("转账金额不能为负");
		}
		if (from.equals(to)) {
			return true; // 转给自己视为无操作
		}
		return transferMany(from, List.of(to), List.of(toName != null ? toName : "未知玩家"), amount);
	}

	/**
	 * 向多个目标各转 amount，原子执行：任一失败（余额不足/溢出）则整体不做修改。
	 * 返回 false 表示来源余额不足。
	 */
	public static synchronized boolean transferMany(UUID from, List<UUID> targets, List<String> names, long amount) {
		requireOpen();
		if (amount < 0) {
			throw new IllegalArgumentException("转账金额不能为负");
		}
		if (targets.isEmpty()) {
			return true;
		}
		long total;
		try {
			total = Math.multiplyExact(amount, targets.size());
		} catch (ArithmeticException e) {
			throw new DatabaseException("转账总额溢出", e);
		}
		boolean oldAutoCommit;
		try {
			oldAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
		} catch (SQLException e) {
			throw new DatabaseException("开启事务失败", e);
		}
		try {
			try (PreparedStatement deduct = connection.prepareStatement("""
					UPDATE economy_accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?
					""")) {
				deduct.setLong(1, total);
				deduct.setString(2, from.toString());
				deduct.setLong(3, total);
				if (deduct.executeUpdate() == 0) {
					connection.rollback();
					return false;
				}
			}
			try (PreparedStatement add = connection.prepareStatement("""
					UPDATE economy_accounts SET balance = balance + ? WHERE uuid = ?
					""")) {
				for (int i = 0; i < targets.size(); i++) {
					ensureAccount(targets.get(i), names.get(i));
					add.setLong(1, amount);
					add.setString(2, targets.get(i).toString());
					add.executeUpdate();
				}
			}
			connection.commit();
			return true;
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
				// 回滚失败时交由上层异常处理。
			}
			throw new DatabaseException("转账失败", e);
		} finally {
			try {
				connection.setAutoCommit(oldAutoCommit);
			} catch (SQLException e) {
				throw new DatabaseException("恢复自动提交失败", e);
			}
		}
	}

	private static void deleteAccount(UUID uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM economy_accounts WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			ps.executeUpdate();
		}
		// 级联清理交易流水（自检数据清理与账户删除保持一致性）
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM economy_transactions WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			ps.executeUpdate();
		}
	}

	// ---------- 排行榜 ----------

	public static synchronized int accountCount() {
		requireOpen();
		try (Statement statement = connection.createStatement();
			 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM economy_accounts WHERE uuid <> '"
						+ LEGACY_SERVER_ACCOUNT_UUID + "'")) {
			if (rs.next()) {
				return rs.getInt(1);
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询账户总数失败", e);
		}
		return 0;
	}

	public static synchronized List<AccountEntry> topAccounts(int limit, int offset) {
		requireOpen();
		List<AccountEntry> result = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("""
				SELECT uuid, name, balance FROM economy_accounts
				WHERE uuid <> ?
				ORDER BY balance DESC, name ASC
				LIMIT ? OFFSET ?
				""")) {
			ps.setString(1, LEGACY_SERVER_ACCOUNT_UUID.toString());
			ps.setInt(2, limit);
			ps.setInt(3, offset);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					result.add(new AccountEntry(
							UUID.fromString(rs.getString(1)),
							rs.getString(2),
							rs.getLong(3)));
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询排行榜失败", e);
		}
		return result;
	}

	/** 服务器总资产（分）：所有玩家账户余额之和（排除历史遗留的服务器公共账户行）。 */
	public static synchronized long totalPlayerAssets() {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT COALESCE(SUM(balance), 0) FROM economy_accounts WHERE uuid <> ?")) {
			ps.setString(1, LEGACY_SERVER_ACCOUNT_UUID.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getLong(1);
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询总资产失败", e);
		}
		return 0;
	}

	/** 账户分页查询结果。 */
	public record AccountPage(int total, List<AccountEntry> list) {
	}

	/**
	 * 分页查询账户（管理前端用）：query 为空 = 全部；否则按名字/UUID 模糊匹配。
	 * 余额允许为负（管理回滚可能造成负数），按余额倒序。
	 */
	public static synchronized AccountPage listAccounts(String query, int limit, int offset) {
		requireOpen();
		String where = " WHERE uuid <> ?";
		if (query != null && !query.isEmpty()) {
			where += " AND (name LIKE ? OR uuid LIKE ?)";
		}
		int total;
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT COUNT(*) FROM economy_accounts" + where)) {
			int idx = 1;
			ps.setString(idx++, LEGACY_SERVER_ACCOUNT_UUID.toString());
			if (query != null && !query.isEmpty()) {
				String like = "%" + query + "%";
				ps.setString(idx++, like);
				ps.setString(idx, like);
			}
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				total = rs.getInt(1);
			}
		} catch (SQLException e) {
			throw new DatabaseException("统计账户失败", e);
		}
		List<AccountEntry> list = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("""
				SELECT uuid, name, balance FROM economy_accounts
				""" + where + " ORDER BY balance DESC, name ASC LIMIT ? OFFSET ?")) {
			int idx = 1;
			ps.setString(idx++, LEGACY_SERVER_ACCOUNT_UUID.toString());
			if (query != null && !query.isEmpty()) {
				String like = "%" + query + "%";
				ps.setString(idx++, like);
				ps.setString(idx++, like);
			}
			ps.setInt(idx++, limit);
			ps.setInt(idx, offset);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					list.add(new AccountEntry(
							UUID.fromString(rs.getString(1)),
							rs.getString(2),
							rs.getLong(3)));
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询账户列表失败", e);
		}
		return new AccountPage(total, list);
	}

	// ---------- 家（homes） ----------

	/** 家的坐标条目。world 为维度 ID 字符串（如 "minecraft:overworld"）。 */
	public record HomeEntry(String name, String world, double x, double y, double z, long created) {
	}

	/** 设置/覆盖指定名称的家；created 为当前毫秒时间戳，用于“最近设置的家”判定。 */
	public static synchronized void setHome(UUID uuid, String name, String world, double x, double y, double z) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO homes (uuid, name, world, x, y, z, created) VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(uuid, name) DO UPDATE SET
					world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z,
					created = excluded.created
				""")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			ps.setString(3, world);
			ps.setDouble(4, x);
			ps.setDouble(5, y);
			ps.setDouble(6, z);
			ps.setLong(7, System.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("设置家失败", e);
		}
	}

	/** 查询指定名称的家；不存在返回 null。 */
	public static synchronized HomeEntry getHome(UUID uuid, String name) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement("""
				SELECT name, world, x, y, z, created FROM homes WHERE uuid = ? AND name = ?
				""")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return new HomeEntry(rs.getString(1), rs.getString(2),
							rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6));
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询家失败", e);
		}
		return null;
	}

	/** 查询玩家全部家，按设置时间倒序（第一项为最近设置的家）。 */
	public static synchronized List<HomeEntry> getHomes(UUID uuid) {
		requireOpen();
		List<HomeEntry> result = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("""
				SELECT name, world, x, y, z, created FROM homes WHERE uuid = ? ORDER BY created DESC
				""")) {
			ps.setString(1, uuid.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					result.add(new HomeEntry(rs.getString(1), rs.getString(2),
							rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6)));
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询家列表失败", e);
		}
		return result;
	}

	/** 玩家已设置的家数量。 */
	public static synchronized int countHomes(UUID uuid) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT COUNT(*) FROM homes WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("统计家数量失败", e);
		}
		return 0;
	}

	/** 删除指定名称的家；返回是否真的删除了。 */
	public static synchronized boolean removeHome(UUID uuid, String name) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM homes WHERE uuid = ? AND name = ?")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			throw new DatabaseException("删除家失败", e);
		}
	}

	// ---------- 死亡点（back） ----------

	/** 最近死亡点（每个玩家仅保留一个）。 */
	public record BackPoint(String world, double x, double y, double z) {
	}

	/** 记录/覆盖玩家最近死亡点。 */
	public static synchronized void setBackPoint(UUID uuid, String world, double x, double y, double z) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO back_points (uuid, world, x, y, z) VALUES (?, ?, ?, ?, ?)
				ON CONFLICT(uuid) DO UPDATE SET
					world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z
				""")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, world);
			ps.setDouble(3, x);
			ps.setDouble(4, y);
			ps.setDouble(5, z);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("记录死亡点失败", e);
		}
	}

	/** 查询最近死亡点；不存在返回 null。 */
	public static synchronized BackPoint getBackPoint(UUID uuid) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT world, x, y, z FROM back_points WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return new BackPoint(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4));
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询死亡点失败", e);
		}
		return null;
	}

	/** 清除玩家最近死亡点（/back 使用成功后调用）。 */
	public static synchronized void clearBackPoint(UUID uuid) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM back_points WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("清除死亡点失败", e);
		}
	}

	// ---------- 交易记录（买卖流水） ----------

	/**
	 * 一笔买卖流水。uuid/name 为玩家；item_id 为注册表 ID（如 "minecraft:diamond"），
	 * item_name 为显示名（含自定义名），item_data 为物品完整组件数据
	 * （ItemStack.CODEC 编码的 JSON 字符串，含 NBT/组件；旧记录可能为 null），
	 * price 为本次交易金额（分），balance 为交易后账户余额（分），time 为毫秒时间戳。
	 */
	public record TransactionEntry(long id, UUID uuid, String name, String type, String channel,
			String itemId, String itemName, String itemData, int count, long price,
			long balance, long time) {
	}

	/**
	 * 记录一笔购买/出售流水（BUY=花钱获得物品，SELL=物品消失换钱）。
	 * itemData 为物品完整组件数据（可为 null，旧版本记录没有）。
	 * 调用方负责传入正确的类型/渠道/物品与金额；失败抛 {@link DatabaseException}，
	 * 调用方按需静默（记录失败不应阻断资金结算）。
	 */
	public static synchronized void recordTransaction(UUID uuid, String name, String type, String channel,
			String itemId, String itemName, String itemData, int count, long price) {
		requireOpen();
		ensureAccount(uuid, name);
		long balance = getBalance(uuid);
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO economy_transactions
					(uuid, name, type, channel, item_id, item_name, item_data, count, price, balance, time)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			ps.setString(3, type);
			ps.setString(4, channel);
			ps.setString(5, itemId == null ? "" : itemId);
			ps.setString(6, itemName);
			ps.setString(7, itemData);
			ps.setInt(8, count);
			ps.setLong(9, price);
			ps.setLong(10, balance);
			ps.setLong(11, System.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DatabaseException("记录交易失败", e);
		}
	}

	/**
	 * 记录非买卖类资金流水（转账/管理操作/系统扣费/红包等）：item 字段用描述占位，
	 * price 为资金变化量（分，入账为正、扣款为负）。**约定：一切资金变化行为
	 * 都必须经此记录**（后续新功能同样遵守，见 docs/architecture/README.md 全局约定）。
	 */
	public static synchronized void recordMoneyLog(UUID uuid, String name, String type, String channel,
			String description, long price) {
		recordTransaction(uuid, name, type, channel, "", description, null, 0, price);
	}

	/** 查询玩家最近的交易流水（按时间倒序，limit 条）。 */
	public static synchronized List<TransactionEntry> recentTransactions(UUID uuid, int limit) {
		requireOpen();
		List<TransactionEntry> result = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("""
				SELECT id, uuid, name, type, channel, item_id, item_name, item_data, count, price, balance, time
				FROM economy_transactions WHERE uuid = ?
				ORDER BY time DESC, id DESC LIMIT ?
				""")) {
			ps.setString(1, uuid.toString());
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					result.add(readTransaction(rs));
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询交易流水失败", e);
		}
		return result;
	}

	/** 玩家的交易流水总数。 */
	public static synchronized int transactionCount(UUID uuid) {
		requireOpen();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT COUNT(*) FROM economy_transactions WHERE uuid = ?")) {
			ps.setString(1, uuid.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("统计交易流水失败", e);
		}
		return 0;
	}

	/** 交易流水分页查询结果。 */
	public record TransactionPage(int total, List<TransactionEntry> list) {
	}

	/**
	 * 分页查询资金流水（管理前端用）：uuid 为空 = 全部玩家；types/channels 为
	 * 多值筛选（空列表 = 不过滤，非空 = IN 匹配）；按时间倒序。
	 */
	public static synchronized TransactionPage queryTransactions(UUID uuid, List<String> types,
			List<String> channels, int limit, int offset) {
		requireOpen();
		StringBuilder where = new StringBuilder(" WHERE 1=1");
		List<String> params = new ArrayList<>();
		if (uuid != null) {
			where.append(" AND uuid = ?");
			params.add(uuid.toString());
		}
		if (types != null && !types.isEmpty()) {
			where.append(" AND type IN (").append(placeholders(types.size())).append(")");
			params.addAll(types);
		}
		if (channels != null && !channels.isEmpty()) {
			where.append(" AND channel IN (").append(placeholders(channels.size())).append(")");
			params.addAll(channels);
		}
		int total;
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT COUNT(*) FROM economy_transactions" + where)) {
			bindParams(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				total = rs.getInt(1);
			}
		} catch (SQLException e) {
			throw new DatabaseException("统计交易流水失败", e);
		}
		List<TransactionEntry> list = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("""
				SELECT id, uuid, name, type, channel, item_id, item_name, item_data, count, price, balance, time
				FROM economy_transactions""" + where + " ORDER BY time DESC, id DESC LIMIT ? OFFSET ?")) {
			bindParams(ps, params);
			ps.setInt(params.size() + 1, limit);
			ps.setInt(params.size() + 2, offset);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					list.add(readTransaction(rs));
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("查询交易流水失败", e);
		}
		return new TransactionPage(total, list);
	}

	private static String placeholders(int n) {
		return String.join(",", java.util.Collections.nCopies(n, "?"));
	}

	private static void bindParams(PreparedStatement ps, List<String> params) throws SQLException {
		for (int i = 0; i < params.size(); i++) {
			ps.setString(i + 1, params.get(i));
		}
	}

	private static TransactionEntry readTransaction(ResultSet rs) throws SQLException {
		return new TransactionEntry(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
				rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
				rs.getInt(9), rs.getLong(10), rs.getLong(11), rs.getLong(12));
	}

	/** 删除交易记录后的资金回滚结果（newBalance 为回滚后余额，可为负）。 */
	public record RollbackResult(long id, UUID uuid, String name, String type,
			long price, long newBalance) {
	}

	/**
	 * 删除交易记录并同步回滚玩家资金（原子事务）：删除一条 BUY 记录 = 撤销
	 * 该笔购买 → 退款收回（余额 +price）；删除一条 SELL 记录 = 撤销该笔卖出 →
	 * 卖出所得扣回（余额 -price）。**仅 BUY/SELL（有物品成交的交易）回滚资金**；
	 * 其他类型（转账/管理/扣费/红包）删除时仅删记录、不动资金（撤销它们需要
	 * 复杂的多方调整，超出「删除交易」语义）。回滚后余额可能为负（账户表已允许）。
	 * 返回每条记录的处理结果；不存在的 id 跳过。
	 */
	public static synchronized List<RollbackResult> deleteTransactionsWithRollback(List<Long> ids) {
		requireOpen();
		if (ids.isEmpty()) {
			return List.of();
		}
		List<RollbackResult> results = new ArrayList<>();
		boolean oldAutoCommit;
		try {
			oldAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
		} catch (SQLException e) {
			throw new DatabaseException("开启事务失败", e);
		}
		try {
			for (long id : ids) {
				TransactionEntry tx = null;
				try (PreparedStatement ps = connection.prepareStatement("""
						SELECT uuid, name, type, price FROM economy_transactions WHERE id = ?
						""")) {
					ps.setLong(1, id);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							tx = new TransactionEntry(id, UUID.fromString(rs.getString(1)),
									rs.getString(2), rs.getString(3), null, null, null,
									null, 0, rs.getLong(4), 0, 0);
						}
					}
				}
				if (tx == null) {
					continue; // 记录不存在：跳过
				}
				try (PreparedStatement ps = connection.prepareStatement(
						"DELETE FROM economy_transactions WHERE id = ?")) {
					ps.setLong(1, id);
					ps.executeUpdate();
				}
				// 回滚资金（仅 BUY/SELL）：BUY 退款收回、SELL 所得扣回（允许余额为负）
				long delta = 0;
				if (TYPE_BUY.equals(tx.type())) {
					delta = tx.price();
				} else if (TYPE_SELL.equals(tx.type())) {
					delta = -tx.price();
				}
				long newBalance;
				if (delta != 0) {
					try (PreparedStatement ps = connection.prepareStatement("""
							UPDATE economy_accounts SET balance = balance + ? WHERE uuid = ?
							""")) {
						ps.setLong(1, delta);
						ps.setString(2, tx.uuid().toString());
						ps.executeUpdate();
					}
				}
				try (PreparedStatement ps = connection.prepareStatement(
						"SELECT balance FROM economy_accounts WHERE uuid = ?")) {
					ps.setString(1, tx.uuid().toString());
					try (ResultSet rs = ps.executeQuery()) {
						rs.next();
						newBalance = rs.getLong(1);
					}
				}
				results.add(new RollbackResult(id, tx.uuid(), tx.name(), tx.type(), tx.price(), newBalance));
			}
			connection.commit();
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
				// 回滚失败时交由上层异常处理。
			}
			throw new DatabaseException("删除交易记录回滚失败", e);
		} finally {
			try {
				connection.setAutoCommit(oldAutoCommit);
			} catch (SQLException e) {
				throw new DatabaseException("恢复自动提交失败", e);
			}
		}
		return results;
	}

	// ---------- 公告 ----------

	public static synchronized String getAnnouncement() {
		if (connection == null) {
			return null;
		}
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT value FROM economy_settings WHERE key = ?")) {
			ps.setString(1, KEY_ANNOUNCEMENT);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getString(1);
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("读取公告失败", e);
		}
		return null;
	}

	public static synchronized void setAnnouncement(String content) {
		requireOpen();
		try {
			if (content == null || content.isEmpty()) {
				try (PreparedStatement ps = connection.prepareStatement(
						"DELETE FROM economy_settings WHERE key = ?")) {
					ps.setString(1, KEY_ANNOUNCEMENT);
					ps.executeUpdate();
				}
			} else {
				try (PreparedStatement ps = connection.prepareStatement("""
						INSERT INTO economy_settings (key, value) VALUES (?, ?)
						ON CONFLICT(key) DO UPDATE SET value = excluded.value
						""")) {
					ps.setString(1, KEY_ANNOUNCEMENT);
					ps.setString(2, content);
					ps.executeUpdate();
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("设置公告失败", e);
		}
	}

	private static void requireOpen() {
		if (connection == null) {
			throw new DatabaseException("数据库尚未就绪");
		}
	}

	/** 玩家账户信息（排行榜用）。 */
	public record AccountEntry(UUID uuid, String name, long balance) {
	}

	/** 数据层不可恢复错误；命令层捕获后向玩家返回可读提示。 */
	public static class DatabaseException extends RuntimeException {
		public DatabaseException(String message) {
			super(message);
		}

		public DatabaseException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
