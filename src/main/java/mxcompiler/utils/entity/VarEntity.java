package mxcompiler.utils.entity;

import mxcompiler.ast.declaration.VarDeclNode;
import mxcompiler.type.Type;
import mxcompiler.type.VarType;
import mxcompiler.utils.Dump;

public class VarEntity extends Entity {
	// private IRRegister irRegister;
	// private boolean unUsed = false;

	// FIX: what is offset for?
	// FIX: what is member for?
	// FIX: why private, I change into public
	// public int offset;
	private boolean isMember = false;
	private String className;
	public boolean isGlobal = false;

	public VarEntity(String name, Type type) {
		super(name, (type instanceof VarType) ? type : new VarType(type));
	}

	public VarEntity(String name, Type type, String className) {
		super(name, (type instanceof VarType) ? type : new VarType(type));
		isMember = true;
		this.className = className;
	}

	public VarEntity(VarDeclNode node) {
		super(node.getName(), new VarType(node.getType().getType()));
	}

	public VarEntity(VarDeclNode node, String className) {
		super(node.getName(), new VarType(node.getType().getType()));
		// no need to check VarType's baseType
		isMember = true;
		this.className = className;
	}

	public void _dump(Dump d) {
		d.printf("<Var Entity>:  name: %s, Type: %s\n", name, type.toString());
		d.printf(" isMember: %b, ClassName: %s\n", isMember, className);
		d.printf(" isGlobal: %b\n", isGlobal);
	}

	public void setClassName(String className) {
		if (this.className != null) throw new Error("repeat define ClassName");
		this.className = className;
	}
}