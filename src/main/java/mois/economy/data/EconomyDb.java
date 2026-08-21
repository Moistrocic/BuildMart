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
 * 服务器资产是一个独立账户，用固定 UUID 标识，与玩家账户同表存储。
 * 所有命令均在服务端主线程执行，这里仍对每个操作加 synchronized 作为兜底。
 */
public final class EconomyDb {
	/** 服务器资产独立账户的固定 UUID（全零）。 */
	public static final UUID SERVER_ACCOUNT_UUID = new UUID(0L, 0L);
	public static final String SERVER_ACCOUNT_NAME = "服务器资产";

	private static final String KEY_ANNOUNCEMENT = "announcement";

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
						balance INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0)
					)""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS economy_settings (
						key TEXT PRIMARY KEY,
						value TEXT NOT NULL
					)""");
		}
		ensureAccount(SERVER_ACCOUNT_UUID, SERVER_ACCOUNT_NAME);
	}

	/** 数据库自检：写入、读取、转账、余额不足拦截各验证一次，随后清理测试数据。 */
	private static void runSelfTest() throws SQLException {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		try {
			ensureAccount(a, "自检A");
			ensureAccount(b, "自检B");
			long amount = 123456789L; // 1234567.89 元，验证整数分精度
			credit(a, amount);
			if (getBalance(a) != amount) {
				throw new IllegalStateException("余额写入/读取不一致");
			}
			if (!transfer(a, b, 99999L)) {
				throw new IllegalStateException("充足余额转账被拒绝");
			}
			if (getBalance(a) != amount - 99999L || getBalance(b) != 99999L) {
				throw new IllegalStateException("转账后余额不一致");
			}
			if (transfer(b, a, 99999L + 1)) {
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

	/** 入账（无来源），例如服务器资产初始资金。 */
	public static synchronized void credit(UUID uuid, long amount) {
		requireOpen();
		if (amount < 0) {
			throw new IllegalArgumentException("入账金额不能为负");
		}
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

	/** 把账户余额直接设置为指定值（非负）；账户不存在时先创建。 */
	public static synchronized void setBalance(UUID uuid, String name, long balance) {
		requireOpen();
		if (balance < 0) {
			throw new IllegalArgumentException("余额不能为负");
		}
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

	/** 单笔转账；余额不足时返回 false 且不做任何修改。 */
	public static synchronized boolean transfer(UUID from, UUID to, long amount) {
		requireOpen();
		if (amount < 0) {
			throw new IllegalArgumentException("转账金额不能为负");
		}
		if (from.equals(to)) {
			return true; // 转给自己视为无操作
		}
		return transferMany(from, List.of(to), List.of("未知玩家"), amount);
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
	}

	// ---------- 排行榜 ----------

	public static synchronized int accountCount() {
		requireOpen();
		try (Statement statement = connection.createStatement();
			 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM economy_accounts")) {
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
				ORDER BY balance DESC, name ASC
				LIMIT ? OFFSET ?
				""")) {
			ps.setInt(1, limit);
			ps.setInt(2, offset);
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
