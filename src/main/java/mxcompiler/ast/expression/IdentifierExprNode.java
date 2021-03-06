package mxcompiler.ast.expression;

import mxcompiler.ast.*;
import mxcompiler.utils.entity.VarEntity;
import mxcompiler.utils.Dump;

/**
 * For variable identifier in primary expr Or maybe called nameExprNode is
 * better?
 */
public class IdentifierExprNode extends ExprNode {
	@Override
	public void _dump(Dump d) {
		d.printf("<IdentifierExprNode> %s\n", location.toString());
		d.printf("identifier: %s\n", getIdentifier());
	}

	private String identifier;
	private boolean needMemOp = false;
	private boolean checked = false;
	public VarEntity entity = null;

	public IdentifierExprNode(String identifier, Location location) {
		super(location);
		this.identifier = identifier;
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setNeedMemOp(boolean needMemOp) {
		this.needMemOp = needMemOp;
	}

	public boolean isNeedMemOp() {
		return needMemOp;
	}

	public boolean isChecked() {
		return checked;
	}

	public void setChecked(boolean checked) {
		this.checked = checked;
	}

	@Override
	public void accept(ASTVisitor visitor) {
		visitor.visit(this);
	}
}