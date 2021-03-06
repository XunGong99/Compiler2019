package mxcompiler.main.dump;

import java.io.PrintStream;
import java.util.List;

import mxcompiler.ast.statement.*;
import mxcompiler.utils.Dump;
import mxcompiler.ast.*;
import mxcompiler.ast.declaration.*;
import mxcompiler.ast.expression.*;
import mxcompiler.ast.expression.literal.*;
import mxcompiler.ast.expression.lhs.*;
import mxcompiler.ast.expression.unary.*;

public class ASTDump extends Visitor implements Dump {
	private PrintStream os;
	// private int tab; // tabSize
	private final String t = "\t";
	private StringBuilder tab;

	public ASTDump(PrintStream x) {
		os = x;
		// tab = 0;
		tab = new StringBuilder();
	}

	public void addTab() {
		// ++tab;
		tab.append(t);
	}

	public void delTab() {
		// --tab;
		tab.delete(tab.length() - t.length(), tab.length());
	}

	public String getTab() {
		// return t.repeat(tab);
		return tab.toString();
	}

	public void println(String x) {
		os.println(getTab() + x);
	}

	public void printplainln(String x) {
		os.println(x);
	}

	public void printf(String str, Object... args) {
		os.printf(getTab() + str, args);
	}

	public void print(String str) {
		os.print(getTab() + str);
	}

	@Override
	public void visit(ASTNode node) {
		println("AST-Dump START");
		node._dump(this);

		print("declarations are below:");
		if (!node.getDecl().isEmpty()) {
			print("\n");
			// delTab(); // too high
			for (DeclNode decl : node.getDecl())
				visit(decl);
		} else{
			// delTab(); // too high
			println(" null");
		}

		// addTab();
		println("AST-Dump END");
		println("\n\n");
	}

	@Override
	public void visit(TypeNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	// UGLY: can not use repeat
	@Override
	public void visit(ClassDeclNode node) {
		addTab();
		node._dump(this);

		printDeclList(" varDecls:", node.getVar());
		printDeclList(" funcMember:", node.getFunc());

		delTab();
	}

	@Override
	public void visit(FuncDeclNode node) {
		addTab();
		node._dump(this);

		println(" returnType:");
		visit(node.getReturnType());

		printDeclList(" parameterList:", node.getVar());
		println(" body:");
		visit(node.getBody());
		delTab();
	}

	@Override
	public void visit(VarDeclNode node) {
		addTab();
		node._dump(this);

		println(" type:");
		visit(node.getType());
		printExpr(" init:", node.getInit());

		delTab();
	}

	@Override
	public void visit(VarDeclListNode node) {
		node._dump(this);
		visitDeclList(node.getList());
	}

	@Override
	public void visit(ArefExprNode node) {
		addTab();
		node._dump(this);

		printExpr(" arr:", node.getExpr());
		printExpr(" index:", node.getIndex());
		delTab();
	}

	@Override
	public void visit(MemberExprNode node) {
		addTab();
		node._dump(this);
		printExpr(" expr:", node.getExpr());
		delTab();
	}

	@Override
	public void visit(BoolLiteralExprNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(IntLiteralExprNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(StringLiteralExprNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(PrefixExprNode node) {
		addTab();
		node._dump(this);
		printExpr(" expr:", node.getExpr());
		delTab();
	}

	@Override
	public void visit(SuffixExprNode node) {
		addTab();
		node._dump(this);

		printExpr(" expr:", node.getExpr());
		delTab();
	}

	@Override
	public void visit(AssignExprNode node) {
		addTab();
		node._dump(this);
		printExpr(" lhs:", node.getLhs());
		printExpr(" rhs:", node.getRhs());
		delTab();
	}

	@Override
	public void visit(BinaryOpExprNode node) {
		addTab();
		node._dump(this);
		printExpr(" lhs:", node.getLhs());
		printExpr(" rhs:", node.getRhs());
		delTab();
	}

	@Override
	public void visit(FuncallExprNode node) {
		addTab();
		node._dump(this);
		printExpr(" expr:", node.getExpr());
		printExprList(" args:", node.getParam());
		delTab();
	}

	@Override
	public void visit(IdentifierExprNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(NewExprNode node) {
		addTab();
		node._dump(this);
		println(" newType:");
		visit(node.getNewType());
		printExprList("dims:", node.getDims());

		delTab();
	}

	@Override
	public void visit(NullExprNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(ThisExprNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(BlockStmtNode node) {
		addTab();
		node._dump(this);

		println(" stmtsAnddecls: ");
		if (node != null && !node.getAll().isEmpty())
			for (Node n : node.getAll()) {
				visit(n);
			}

		// printStmtList(" stmts:", node.getStmts());

		delTab();
	}

	@Override
	public void visit(BreakStmtNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(ContinueStmtNode node) {
		addTab();
		node._dump(this);
		delTab();
	}

	@Override
	public void visit(ExprStmtNode node) {
		addTab();
		node._dump(this);
		printExpr(" expr:", node.getExpr());
		delTab();
	}

	@Override
	public void visit(ForStmtNode node) {
		addTab();
		node._dump(this);

		printDeclList(" varDecl:", node.getVar());
		printExpr(" init:", node.getInit());
		printExpr(" cond:", node.getCond());
		printExpr(" incr:", node.getIncr());
		printStmt(" body:", node.getBody());
		delTab();
	}

	@Override
	public void visit(IfStmtNode node) {
		addTab();
		node._dump(this);
		printExpr(" cond:", node.getCond());
		printStmt(" then:", node.getThen());
		printStmt(" else:", node.getElse());
		delTab();
	}

	@Override
	public void visit(ReturnStmtNode node) {
		addTab();
		node._dump(this);
		printExpr(" value:", node.getExpr());
		delTab();
	}

	@Override
	public void visit(WhileStmtNode node) {
		addTab();
		node._dump(this);
		printExpr(" cond:", node.getCond());

		printStmt(" body:", node.getBody());

		delTab();
	}

	private void printDeclList(String s, List<? extends DeclNode> list) {
		print(s);
		if (list != null && !list.isEmpty()) {
			printplainln("");
			for (DeclNode param : list) {
				visit(param);
			}
		} else
			printplainln(" null");
	}

	// private void printStmtList(String s, List<StmtNode> list) {
	// 	print(s);
	// 	if (list != null && !list.isEmpty()) {
	// 		printplainln("");
	// 		for (StmtNode param : list) {
	// 			visit(param);
	// 		}
	// 	} else
	// 		printplainln(" null");
	// }

	private void printExprList(String s, List<ExprNode> list) {
		print(s);
		if (list != null && !list.isEmpty()) {
			printplainln("");
			for (ExprNode param : list) {
				visit(param);
			}
		} else
			printplainln(" null");
	}

	private void printStmt(String s, StmtNode stmt) {
		print(s);
		if (stmt != null) {
			printplainln("");
			visit(stmt);
		} else
			printplainln(" null");
	}

	private void printExpr(String s, ExprNode expr) {
		print(s);
		if (expr != null) {
			printplainln("");
			visit(expr);
		} else
			printplainln(" null");
	}

	protected final void visitStmtList(List<? extends StmtNode> stmts) {
		if (!stmts.isEmpty())
			for (StmtNode n : stmts)
				visit(n);
	}

	protected final void visitExprList(List<? extends ExprNode> exprs) {
		if (!exprs.isEmpty())
			for (ExprNode n : exprs)
				visit(n);
	}

	protected final void visitDeclList(List<? extends DeclNode> decls) {
		if (!decls.isEmpty())
			for (DeclNode n : decls)
				visit(n);
	}

}