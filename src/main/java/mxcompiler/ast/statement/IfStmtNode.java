package mxcompiler.ast.statement;

import mxcompiler.ast.expression.ExprNode;
import mxcompiler.ast.*;
import mxcompiler.utils.Dump;

public class IfStmtNode extends StmtNode {
	@Override
	public void _dump(Dump d) {
		d.printf("<IfStmtNode> %s\n", location.toString());
	}

	private ExprNode cond;
	private StmtNode thenBody;
	/** may be null */
	private StmtNode elseBody;

	public IfStmtNode(ExprNode cond, StmtNode thenBody, StmtNode elseBody, Location location) {
		super(location);
		this.cond = cond;
		this.thenBody = thenBody;
		this.elseBody = elseBody; // may be null
	}

	public ExprNode getCond() {
		return cond;
	}

	public StmtNode getThen() {
		return thenBody;
	}

	public StmtNode getElse() {
		return elseBody;
	}

	@Override
	public void accept(ASTVisitor visitor) {
		visitor.visit(this);
	}
}
