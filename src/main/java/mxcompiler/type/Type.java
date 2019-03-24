package mxcompiler.type;

abstract public class Type {
    /** 
     * can get name-string by type.X.toString() method
     * get compare by type.X.compareTo()
    */
    public static enum InnerType {
		INT("int"), 
        BOOL("bool"), 
        STRING("string"), 
        ARRAY("array"), 
        NULL("null"), 
        VOID("void"),
        CLASS("class"), 
		FUNCTION("function"), 
		VARIABLE("variable");

        private String label;
        private InnerType(String label) { this.label = label; }

        public String toString() { return this.label; }
	}
	
	
    public InnerType innerType;
    public InnerType getInnerType() { return innerType; }

	public Type(InnerType inner) {
		this.innerType = inner;
	}

    protected int varSize;
    public int getSize() { return varSize; }

    public boolean isEqual(Type rhs) { return rhs.innerType == this.innerType; }
    /** only for InnerType output(output name) */
    public String toString() { return innerType.toString(); }
    // get type series ... TODO:!   !
}
