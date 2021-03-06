package mxcompiler.utils.type;

import java.util.HashMap;

import mxcompiler.ir.register.RegValue;


abstract public class Type {
	/**
	 * can get name-string by type.X.toString() method get
	 * compare by type.X.compareTo()
	 */
	public static enum InnerType {
		INT("int"), BOOL("bool"), STRING("string"), ARRAY("array"), NULL(""), VOID("void"), CLASS("class"), FUNCTION(
				"function"), VARIABLE("variable"), MNULL("null");

		private String label;

		private InnerType(String label) {
			this.label = label;
		}

		public String toString() {
			return this.label;
		}

		private static final HashMap<String, InnerType> keyMap = new HashMap<String, InnerType>();
		static {
			for (InnerType op : InnerType.values()) {
				keyMap.put(op.label, op);
			}
		}

		public static InnerType get(String key) {
			return keyMap.get(key);
		}
	}

	public InnerType innerType;
	private int size = RegValue.RegSize;

	/**
	 * return default type's memory-size
	 * <p>
	 * (default=1-reg) {@summary because class and array only
	 * store base-addr}
	 */
	public int getRegSize() {
		return size;
	}

	public InnerType getInnerType() {
		return innerType;
	}

	public Type(InnerType inner) {
		this.innerType = inner;
	}

	// protected int varSize;

	// public int getSize() {
	// return varSize;
	// }

	// ATTENTION:
	// NOTE: this can not be rewrite, cause, ifso new Bool Type
	// != new
	// Bool Type
	// have to compare with innerType
	public boolean isEqual(Type rhs) {
		if (this.innerType == InnerType.CLASS && rhs.innerType == InnerType.CLASS) {
			String x = ((ClassType) rhs).getName();
			String y = ((ClassType) this).getName();
			return (x.equals(y));
		}
		while (rhs.innerType.equals(InnerType.ARRAY) && this.innerType.equals(InnerType.ARRAY)) {
			Type x = ((ArrayType) rhs).getBaseType();
			Type y = ((ArrayType) this).getBaseType();
			return x.isEqual(y);
		}
		return rhs.innerType.equals(this.innerType);

	}

	/** only for InnerType output(output name) */
	public String toString() {
		return innerType.toString();
	}
	// get type series ... TODO:! !
}
