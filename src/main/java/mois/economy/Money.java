package mois.economy;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;

/**
 * 资金数值工具：金额统一以“分”（cents, long 整数）为单位在系统内流转，
 * 只在解析用户输入与展示时与十进制字符串互转，全程不使用浮点数，保证两位小数精度。
 */
public final class Money {
	private static final SimpleCommandExceptionType INVALID_AMOUNT =
			new SimpleCommandExceptionType(Component.literal("金额无效：请输入数字"));
	private static final SimpleCommandExceptionType NOT_POSITIVE =
			new SimpleCommandExceptionType(Component.literal("金额必须大于 0"));
	private static final SimpleCommandExceptionType NOT_NEGATIVE =
			new SimpleCommandExceptionType(Component.literal("金额不能为负"));
	private static final SimpleCommandExceptionType TOO_MANY_DECIMALS =
			new SimpleCommandExceptionType(Component.literal("金额最多只能有两位小数"));
	private static final SimpleCommandExceptionType TOO_LARGE =
			new SimpleCommandExceptionType(Component.literal("金额过大"));

	private Money() {
	}

	/**
	 * 把用户输入的十进制金额字符串精确解析为“分”。
	 * 例如 "10" -> 1000、"10.5" -> 1050、"10.00" -> 1000、"0.01" -> 1。
	 * 负数、零、超过两位小数、无法解析或超出 long 范围时抛出命令异常。
	 */
	public static long parseCents(String input) throws CommandSyntaxException {
		return parse(input, false);
	}

	/** 与 {@link #parseCents} 相同，但允许 0（用于 /eco set 清零）。 */
	public static long parseCentsAllowZero(String input) throws CommandSyntaxException {
		return parse(input, true);
	}

	private static long parse(String input, boolean allowZero) throws CommandSyntaxException {
		BigDecimal value;
		try {
			value = new BigDecimal(input.trim());
		} catch (NumberFormatException e) {
			throw INVALID_AMOUNT.create();
		}
		if (allowZero ? value.signum() < 0 : value.signum() <= 0) {
			throw (allowZero ? NOT_NEGATIVE : NOT_POSITIVE).create();
		}
		if (value.scale() > 2) {
			throw TOO_MANY_DECIMALS.create();
		}
		try {
			return value.movePointRight(2).longValueExact();
		} catch (ArithmeticException e) {
			throw TOO_LARGE.create();
		}
	}

	/**
	 * 把“分”格式化为两位小数字符串，如 1000 -> "10.00"、1050 -> "10.50"。
	 * 使用整数运算格式化，避免浮点格式化误差。
	 */
	public static String format(long cents) {
		boolean negative = cents < 0;
		long abs = Math.abs(cents);
		long yuan = abs / 100;
		long fen = abs % 100;
		return (negative ? "-" : "") + yuan + "." + (fen < 10 ? "0" : "") + fen;
	}
}
