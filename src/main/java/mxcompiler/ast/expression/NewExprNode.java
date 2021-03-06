package mxcompiler.ast.expression;

import java.util.List;

import mxcompiler.ast.*;
import mxcompiler.utils.Dump;

/** for creator */
public class NewExprNode extends ExprNode {
	@Override
	public void _dump(Dump d) {
		d.printf("<NewExprNode> %s\n", location.toString());
		d.printf(" numDim: %d\n", num);
	}

	/** newType may still be arrayType(recurrence) */
	private TypeNode newType;
	private List<ExprNode> dims;
	private int num; // for dimension-number

	public NewExprNode(TypeNode newType, List<ExprNode> dims, int num, Location location) {
		super(location);
		this.newType = newType;
		this.dims = dims;
		this.num = num;
	}

	public TypeNode getNewType() {
		return newType;
	}

	public List<ExprNode> getDims() {
		return dims;
	}

	public int getNum() {
		return num;
	}

	@Override
	public void accept(ASTVisitor visitor) {
		visitor.visit(this);
	}
}